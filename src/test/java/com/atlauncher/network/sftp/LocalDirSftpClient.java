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

import java.nio.file.Path;

/**
 * A fake SFTP server backed by a local folder, for tests: "/" is the folder. Moves data in tiny chunks so tests can
 * cancel part way through a file, which leaves the partial file behind like a real server would.
 */
public class LocalDirSftpClient extends LocalFolderClient {
    static final int CHUNK = 4;

    boolean closed = false;

    public LocalDirSftpClient(Path root) {
        super(root, CHUNK);
    }

    @Override
    public void close() {
        closed = true;
    }
}
