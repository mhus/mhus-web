/**
 * Copyright (C) 2015 Mike Hummel (mh@mhus.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mhus.app.web.core;

import java.io.IOException;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

public class CherryServletOutputStream extends ServletOutputStream {

    private ServletOutputStream instance;

    public CherryServletOutputStream(ServletOutputStream outputStream) {
        instance = outputStream;
    }

    @Override
    public void write(int b) throws IOException {
        instance.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        instance.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        instance.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        instance.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
    }

    @Override
    public boolean isReady() {
        return instance.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        instance.setWriteListener(writeListener);
    }
}
