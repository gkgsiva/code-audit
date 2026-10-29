package com.legacyaudit.engine.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One scan at a time per output folder: an operating-system lock on {@code runs/.lock}, so it is
 * released when the process ends, even after a crash; a stale lock file never blocks the next scan.
 */
public final class ScanLock implements AutoCloseable {

    /** Another scan holds the output folder. */
    public static final class ScanBusy extends RuntimeException {
        public ScanBusy(String message) {
            super(message);
        }
    }

    private final FileChannel channel;
    private final FileLock lock;

    private ScanLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ScanLock acquire(Path runsDir) throws IOException {
        Files.createDirectories(runsDir);
        FileChannel ch = FileChannel.open(runsDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        FileLock l;
        try {
            l = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            l = null; // held by this process (another scan thread)
        }
        if (l == null) {
            ch.close();
            throw new ScanBusy("Another scan is running on " + runsDir.getParent() + " (wait for it to finish or cancel it).");
        }
        ch.truncate(0);
        ch.write(ByteBuffer.wrap(String.valueOf(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)), 0);
        return new ScanLock(ch, l);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
