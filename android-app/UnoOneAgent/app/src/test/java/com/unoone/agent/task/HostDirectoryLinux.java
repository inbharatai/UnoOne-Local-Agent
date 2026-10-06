package com.unoone.agent.task;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.IdentityHashMap;
import java.util.Map;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLinux;

/** Robolectric's RandomAccessFile-based Linux.open cannot open directories.
 * Model only that missing host operation, using a real directory channel and force.
 * Production still calls Android Os.open/fstat/fsync/close without any bypass.
 */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
public class HostDirectoryLinux extends ShadowLinux {
    private final Map<FileDescriptor, FileChannel> directories = new IdentityHashMap<>();
    private final Map<FileDescriptor, String> paths = new IdentityHashMap<>();

    @Implementation
    protected synchronized FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        if (!new File(path).isDirectory()) return super.open(path, flags, mode);
        if (flags != OsConstants.O_RDONLY) throw new ErrnoException("open", OsConstants.EINVAL);
        try {
            FileChannel channel = FileChannel.open(new File(path).toPath(), StandardOpenOption.READ);
            FileDescriptor descriptor = new FileDescriptor();
            directories.put(descriptor, channel);
            paths.put(descriptor, path);
            return descriptor;
        } catch (IOException error) { throw new ErrnoException("open", OsConstants.EIO, error); }
    }

    @Implementation
    protected synchronized StructStat fstat(FileDescriptor descriptor) throws ErrnoException {
        String path = paths.get(descriptor);
        return path == null ? super.fstat(descriptor) : super.stat(path);
    }

    @Implementation
    protected synchronized void fsync(FileDescriptor descriptor) throws ErrnoException {
        try {
            FileChannel directory = directories.get(descriptor);
            if (directory == null) descriptor.sync(); else directory.force(true);
        } catch (IOException error) { throw new ErrnoException("fsync", OsConstants.EIO, error); }
    }

    @Implementation
    protected synchronized void close(FileDescriptor descriptor) throws ErrnoException {
        try {
            FileChannel directory = directories.remove(descriptor);
            paths.remove(descriptor);
            if (directory == null) new FileInputStream(descriptor).close(); else directory.close();
        } catch (IOException error) { throw new ErrnoException("close", OsConstants.EIO, error); }
    }
}
