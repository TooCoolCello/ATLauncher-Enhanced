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
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

/**
 * An {@link SftpClient} over a folder on this computer, where "/" is the folder. It lets a server folder on this
 * computer be read the same way as a remote one (importing from a server), and stands in for a server in tests. It
 * behaves like a real server where it matters: rename refuses to replace, and paths can't leave the folder, through
 * .. or through a link. Listings leave links out, so walking the folder can't leave it or loop.
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
        if (!path.startsWith(root) || leavesRoot(path)) {
            throw new NoSuchFileException(remote);
        }
        return path;
    }

    // whether a link on the way to the path, or the path itself, leads out of the folder
    private boolean leavesRoot(Path path) throws IOException {
        for (Path existing = path; existing != null && existing.startsWith(root); existing = existing.getParent()) {
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                return !existing.toRealPath().startsWith(root.toRealPath());
            }
        }
        return false;
    }

    @Override
    public List<RemoteFile> list(String path) throws IOException {
        Path folder = local(path);
        if (!Files.isDirectory(folder)) {
            throw new NoSuchFileException(path);
        }
        Path real = folder.toRealPath();
        List<RemoteFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                // links (and Windows junctions) are left out: one could lead out of the folder, or loop back up it
                if (!isLink(entry, real)) {
                    files.add(file(entry, SftpClient.join(path, entry.getFileName().toString())));
                }
            }
        }
        return files;
    }

    private static boolean isLink(Path entry, Path realFolder) {
        try {
            return !entry.toRealPath().equals(realFolder.resolve(entry.getFileName()));
        } catch (IOException e) {
            // a link to nothing
            return true;
        }
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        Path file;
        try {
            file = local(path);
        } catch (NoSuchFileException e) {
            // outside the folder, so not there
            return null;
        }
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
        copy(local, target, progress);
    }

    @Override
    public void download(String remotePath, Path local, LongPredicate progress) throws IOException {
        Path source = local(remotePath);
        if (!Files.isRegularFile(source)) {
            throw new NoSuchFileException(remotePath);
        }
        copy(source, local, progress);
    }

    // both opened here, so the input is closed even when the output can't be opened
    private void copy(Path from, Path to, LongPredicate progress) throws IOException {
        try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(to)) {
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
