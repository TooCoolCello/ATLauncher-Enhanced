/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2026 ATLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher.network.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

/**
 * A fake SFTP server backed by a local folder, for tests: "/" is the folder. Behaves like a real server where it
 * matters: rename refuses to replace, and a cancelled upload leaves its partial file behind.
 */
public class LocalDirSftpClient implements SftpClient {
    // bytes per progress callback, small so tests can cancel part way through a file
    static final int CHUNK = 4;

    final Path root;
    boolean closed = false;

    public LocalDirSftpClient(Path root) {
        this.root = root;
    }

    Path local(String remote) {
        String relative = remote.replaceFirst("^/+", "");
        return relative.isEmpty() || relative.equals(".") ? root : root.resolve(relative);
    }

    @Override
    public List<RemoteFile> list(String path) throws IOException {
        Path folder = local(path);
        if (!Files.isDirectory(folder)) {
            throw new NoSuchFileException(path);
        }
        List<RemoteFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                files.add(new RemoteFile(name, SftpClient.join(path, name),
                    Files.isDirectory(entry) ? 0 : Files.size(entry),
                    Files.getLastModifiedTime(entry).toMillis(), Files.isDirectory(entry)));
            }
        }
        return files;
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        Path file = local(path);
        if (!Files.exists(file)) {
            return null;
        }
        return new RemoteFile(file.getFileName().toString(), path, Files.isDirectory(file) ? 0 : Files.size(file),
            Files.getLastModifiedTime(file).toMillis(), Files.isDirectory(file));
    }

    @Override
    public void upload(Path local, String remotePath, LongPredicate progress) throws IOException {
        Path target = local(remotePath);
        if (!Files.isDirectory(target.getParent())) {
            throw new NoSuchFileException(remotePath);
        }
        copy(Files.newInputStream(local), Files.newOutputStream(target), progress);
    }

    @Override
    public void download(String remotePath, Path local, LongPredicate progress) throws IOException {
        Path source = local(remotePath);
        if (!Files.isRegularFile(source)) {
            throw new NoSuchFileException(remotePath);
        }
        copy(Files.newInputStream(source), Files.newOutputStream(local), progress);
    }

    private static void copy(InputStream input, OutputStream output, LongPredicate progress) throws IOException {
        try (InputStream in = input; OutputStream out = output) {
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (progress != null && !progress.test(read)) {
                    throw new InterruptedIOException("Cancelled");
                }
            }
        }
    }

    @Override
    public void mkdirs(String path) throws IOException {
        Files.createDirectories(local(path));
    }

    @Override
    public void remove(String path) throws IOException {
        Files.delete(local(path));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        if (Files.exists(local(to))) {
            throw new FileAlreadyExistsException(to);
        }
        Files.move(local(from), local(to));
    }

    @Override
    public void close() {
        closed = true;
    }
}
