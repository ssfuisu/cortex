package org.cortex.terminal.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The file calls of android.system.Os that Cortex makes, done on the test machine's file system.
 * Robolectric itself leaves symlink, rename and chmod without effect and has lstat follow links,
 * so the set-up steps would seem to succeed without doing anything.
 */
@Implements(Os.class)
public class ShadowJavaFilesOs {
    // Linux errno values
    private static final int ENOENT = 2;
    private static final int EIO = 5;
    private static final int EACCES = 13;
    private static final int EEXIST = 17;
    private static final int ENOTEMPTY = 39;

    private static ErrnoException errno(String call, IOException e) {
        int code = EIO;
        if (e instanceof NoSuchFileException) code = ENOENT;
        else if (e instanceof FileAlreadyExistsException) code = EEXIST;
        else if (e instanceof DirectoryNotEmptyException) code = ENOTEMPTY;
        else if (e instanceof AccessDeniedException) code = EACCES;
        return new ErrnoException(call, code);
    }

    @Implementation
    protected static void symlink(String oldPath, String newPath) throws ErrnoException {
        try {
            Files.createSymbolicLink(Paths.get(newPath), Paths.get(oldPath));
        } catch (IOException e) {
            throw errno("symlink", e);
        }
    }

    @Implementation
    protected static String readlink(String path) throws ErrnoException {
        try {
            return Files.readSymbolicLink(Paths.get(path)).toString();
        } catch (IOException e) {
            throw errno("readlink", e);
        } catch (UnsupportedOperationException | SecurityException e) {
            throw new ErrnoException("readlink", EIO);
        }
    }

    @Implementation
    protected static void rename(String oldPath, String newPath) throws ErrnoException {
        try {
            Files.move(Paths.get(oldPath), Paths.get(newPath), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw errno("rename", e);
        }
    }

    @Implementation
    protected static void remove(String path) throws ErrnoException {
        try {
            Files.delete(Paths.get(path));
        } catch (IOException e) {
            throw errno("remove", e);
        }
    }

    @Implementation
    protected static void chmod(String path, int mode) throws ErrnoException {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] bits = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ
        };
        for (int i = 0; i < bits.length; i++) {
            if ((mode & (1 << i)) != 0) permissions.add(bits[i]);
        }
        try {
            Files.setPosixFilePermissions(Paths.get(path), permissions);
        } catch (IOException e) {
            throw errno("chmod", e);
        }
    }

    @Implementation
    protected static StructStat lstat(String path) throws ErrnoException {
        return stat(Paths.get(path), "lstat", LinkOption.NOFOLLOW_LINKS);
    }

    @Implementation
    protected static StructStat stat(String path) throws ErrnoException {
        return stat(Paths.get(path), "stat");
    }

    private static StructStat stat(Path path, String call, LinkOption... options) throws ErrnoException {
        try {
            Map<String, Object> a = Files.readAttributes(path, "unix:dev,ino,mode,nlink,uid,gid,rdev,size", options);
            long mtime = Files.getLastModifiedTime(path, options).toMillis() / 1000;
            long size = ((Number) a.get("size")).longValue();
            return new StructStat(
                ((Number) a.get("dev")).longValue(), ((Number) a.get("ino")).longValue(),
                ((Number) a.get("mode")).intValue(), ((Number) a.get("nlink")).longValue(),
                ((Number) a.get("uid")).intValue(), ((Number) a.get("gid")).intValue(),
                ((Number) a.get("rdev")).longValue(), size,
                mtime, mtime, mtime, 4096, (size + 511) / 512);
        } catch (IOException e) {
            throw errno(call, e);
        }
    }
}
