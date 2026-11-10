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

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * One open SFTP connection. {@link JschSftpClient} is the real one; tests use a fake, so code built on this (uploads,
 * and later sync and import) can be tested without a network.
 *
 * <p>
 * Remote paths use forward slashes. Every failure is an IOException whose message can be shown to the user as is.
 * Progress callbacks get the number of bytes moved since the last call and return false to cancel, which then throws
 * {@link InterruptedIOException}.
 */
public interface SftpClient extends Closeable {
    /**
     * A file or folder on the server.
     */
    final class RemoteFile {
        public final String name;
        public final String path;
        public final long size;
        // milliseconds since the epoch
        public final long modified;
        public final boolean directory;

        public RemoteFile(String name, String path, long size, long modified, boolean directory) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.directory = directory;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /**
     * The folder's contents, without . and ..
     *
     * @throws NoSuchFileException if the folder doesn't exist
     */
    List<RemoteFile> list(String path) throws IOException;

    /**
     * @return the file or folder, or null if nothing is there
     */
    RemoteFile stat(String path) throws IOException;

    void upload(Path local, String remotePath, LongPredicate progress) throws IOException;

    void download(String remotePath, Path local, LongPredicate progress) throws IOException;

    /**
     * Creates the folder and any missing parents; nothing happens if it already exists.
     */
    void mkdirs(String path) throws IOException;

    /**
     * Removes a file, or an empty folder.
     */
    void remove(String path) throws IOException;

    /**
     * Renames or moves a file or folder. Fails if something is already at the destination.
     */
    void rename(String from, String to) throws IOException;

    @Override
    void close();

    /**
     * Joins a folder and a relative path with forward slashes.
     */
    static String join(String base, String relative) {
        String rel = relative.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (base == null || base.isEmpty() || base.equals(".")) {
            return rel;
        }
        return base.endsWith("/") ? base + rel : base + "/" + rel;
    }

    /**
     * @return the folder a path is in: "/" for a file at the root, and "" for a relative name with no folder
     */
    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        return slash == 0 ? "/" : path.substring(0, slash);
    }
}
