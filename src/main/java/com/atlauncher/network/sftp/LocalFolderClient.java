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
 * An {@link SftpClient} over a folder on this computer, where "/" is the folder. It lets a server folder on this
 * computer be read the same way as a remote one (importing from a server), and stands in for a server in tests. It
 * behaves like a real server where it matters: rename refuses to replace, and paths can't leave the folder.
 */
public class LocalFolderClient implements SftpClient {
    private final Path root;
    // bytes per progress callback
    private final int chunk;

    public LocalFolderClient(Path root) {
        this(root, 64 * 1024);
    }

    protected LocalFolderClient(Path root, int chunk) {
        this.root = root.toAbsolutePath().normalize();
        this.chunk = chunk;
    }

    private Path local(String remote) throws IOException {
        String relative = remote.replace('\\', '/').replaceFirst("^/+", "");
        Path path = relative.isEmpty() || relative.equals(".") ? root : root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new NoSuchFileException(remote);
        }
        return path;
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
                files.add(file(entry, SftpClient.join(path, entry.getFileName().toString())));
            }
        }
        return files;
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        Path file = local(path);
        return Files.exists(file) ? file(file, path) : null;
    }

    private static RemoteFile file(Path file, String path) throws IOException {
        boolean directory = Files.isDirectory(file);
        return new RemoteFile(file.getFileName() == null ? "" : file.getFileName().toString(), path,
            directory ? 0 : Files.size(file), Files.getLastModifiedTime(file).toMillis(), directory);
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

    private void copy(InputStream input, OutputStream output, LongPredicate progress) throws IOException {
        try (InputStream in = input; OutputStream out = output) {
            byte[] buffer = new byte[chunk];
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
    }
}
