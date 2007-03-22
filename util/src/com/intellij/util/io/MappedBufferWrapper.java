/*
 * @author max
 */
package com.intellij.util.io;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ConcurrencyUtil;
import org.jetbrains.annotations.NonNls;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public abstract class MappedBufferWrapper {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.io.MappedBufferWrapper");

  @NonNls public static final String BBU_TEMP_FILE_NAME = "BBU";
  @NonNls public static final String CLEANER_METHOD = "cleaner";
  @NonNls public static final String CLEAN_METHOD = "clean";
  @NonNls public static final String VIEWED_BUFFER_FIELD = "viewedBuffer";
  @NonNls public static final String FINALIZE_METHOD = "finalize";

  protected File myFile;
  protected long myPosition;
  protected long myLength;

  private final AtomicReference<ByteBuffer> myBuffer;

  private volatile int myAccessCounter = 0;
  private int myAccessCounterDisposerLastCheckedMe = -1;

  private static List<MappedBufferWrapper> ourMappedWrappers = new ArrayList<MappedBufferWrapper>();

  private static final Object LOCK = new Object();

  static {
    ScheduledExecutorService service = ConcurrencyUtil.newSingleScheduledThreadExecutor("Memory mapped files disposer");
    service.scheduleWithFixedDelay(new Runnable() {
      public void run() {
        MappedBufferWrapper[] wrappers;
        synchronized (LOCK) {
          wrappers = ourMappedWrappers.toArray(new MappedBufferWrapper[ourMappedWrappers.size()]);
        }

        for (MappedBufferWrapper wrapper : wrappers) {
          if (wrapper.myAccessCounter == wrapper.myAccessCounterDisposerLastCheckedMe) {
            wrapper.unmap();
          }
          else {
            wrapper.myAccessCounterDisposerLastCheckedMe = wrapper.myAccessCounter;
          }
        }
      }
    }, 60, 60, TimeUnit.SECONDS);
  }

  public MappedBufferWrapper(final File file, final long pos, final long length) {
    myFile = file;
    myPosition = pos;
    myLength = length;
    myBuffer = new AtomicReference<ByteBuffer>(null);
  }

  protected abstract MappedByteBuffer map();

  public final void unmap() {
    synchronized (LOCK) {
      ByteBuffer buf = myBuffer.getAndSet(null);
      if (buf != null) {
        ourMappedWrappers.remove(this);
        if (buf instanceof MappedByteBuffer) {
          ((MappedByteBuffer)buf).force();
        }

        if (!unmapMappedByteBuffer142b19(this, buf)) {
          unmapMappedByteBuffer141(this, buf);
        }
      }
    }
  }

  /*
   * An assumption made here that any retreiver of the buffer will not use it for time longer than 60 seconds.
   */
  public ByteBuffer buf() {
    ByteBuffer buffer = null;
    try {
      buffer = myBuffer.getAndSet(null);
      myAccessCounter++;
      if (buffer == null) {
        synchronized (LOCK) {
          buffer = map();

          ourMappedWrappers.add(this);
        }
      }

      return buffer;
    }
    finally {
      myBuffer.set(buffer);
    }
  }


  private static void unmapMappedByteBuffer141(MappedBufferWrapper holder, ByteBuffer buffer) {
    unmapBuffer(buffer);
    assert holder.myBuffer.get() == null;

    boolean needGC = SystemInfo.JAVA_VERSION.startsWith("1.4.0");

    if (!needGC) {
      try {
        File newFile = File.createTempFile(BBU_TEMP_FILE_NAME, "", holder.myFile.getParentFile());
        newFile.delete();
        if (!holder.myFile.renameTo(newFile)) {
          needGC = true;
        }
        else {
          newFile.renameTo(holder.myFile);
        }
      }
      catch (IOException e) {
        needGC = true;
      }
    }

    if (needGC) {
      System.gc();
      System.runFinalization();
    }
  }

  private static boolean unmapMappedByteBuffer142b19(MappedBufferWrapper holder, ByteBuffer buffer) {
    assert holder.myBuffer.get() == null;

    if (clean(buffer)) {
      return true;
    }

    return false;
  }

  public static boolean clean(final Object buffer) {
    return AccessController.doPrivileged(new PrivilegedAction<Object>() {
      public Object run() {
        try {
          Method getCleanerMethod = buffer.getClass().getMethod(CLEANER_METHOD, ArrayUtil.EMPTY_CLASS_ARRAY);
          getCleanerMethod.setAccessible(true);
          Object cleaner = getCleanerMethod.invoke(buffer, ArrayUtil.EMPTY_OBJECT_ARRAY); // cleaner is actually of sun.misc.Cleaner
          Class cleanerClass = Class.forName("sun.misc.Cleaner");
          Method cleanMethod = cleanerClass.getMethod(CLEAN_METHOD, ArrayUtil.EMPTY_CLASS_ARRAY);
          cleanMethod.invoke(cleaner, ArrayUtil.EMPTY_OBJECT_ARRAY);
        }
        catch (Exception e) {
          return buffer;
        }
        return null;
      }
    }) == null;
  }

  private static void unmapBuffer(ByteBuffer buffer) {
    try {
      Field field = Class.forName("java.nio.DirectByteBuffer").getDeclaredField(VIEWED_BUFFER_FIELD);
      field.setAccessible(true);
      if (field.get(buffer) instanceof MappedByteBuffer) {
        unmapBuffer((MappedByteBuffer)field.get(buffer));
        return;
      }


      Method finalizeMethod = Object.class.getDeclaredMethod(FINALIZE_METHOD, ArrayUtil.EMPTY_CLASS_ARRAY);
      finalizeMethod.setAccessible(true);
      finalizeMethod.invoke(buffer, ArrayUtil.EMPTY_OBJECT_ARRAY);
    }
    catch (Exception e) {
      LOG.error(e);
    }
  }

  public boolean isMapped() {
    return myBuffer != null;
  }

  public void flush() {
    final ByteBuffer buffer = myBuffer.getAndSet(null);
    try {
      if (buffer != null) {
        if (buffer instanceof MappedByteBuffer) {
          final MappedByteBuffer mappedByteBuffer = (MappedByteBuffer)buffer;
          mappedByteBuffer.force();
        }
      }
    }
    finally {
      myBuffer.set(buffer);
    }
  }
}
