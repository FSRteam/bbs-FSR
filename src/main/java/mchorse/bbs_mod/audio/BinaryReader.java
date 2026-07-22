package mchorse.bbs_mod.audio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public abstract class BinaryReader
{
    public byte[] buf = new byte[4];

    public static int b2i(byte b0, byte b1, byte b2, byte b3)
    {
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }

    public int fourChars(char c0, char c1, char c2, char c3)
    {
        return ((c3 << 24) & 0xff000000) | ((c2 << 16) & 0x00ff0000) | ((c1 << 8) & 0x0000ff00) | (c0 & 0x000000ff);
    }

    public int fourChars(String string)
    {
        char[] chars = string.toCharArray();

        if (chars.length != 4)
        {
            throw new IllegalArgumentException("FourCC must contain exactly four characters: " + string);
        }

        return this.fourChars(chars[0], chars[1], chars[2], chars[3]);
    }

    public String readFourString(InputStream stream) throws IOException
    {
        this.readFully(stream, this.buf, 0, 4);

        return new String(this.buf, StandardCharsets.US_ASCII);
    }

    public int readInt(InputStream stream) throws IOException
    {
        this.readFully(stream, this.buf, 0, 4);

        return b2i(this.buf[0], this.buf[1], this.buf[2], this.buf[3]);
    }

    public long readUnsignedInt(InputStream stream) throws IOException
    {
        return Integer.toUnsignedLong(this.readInt(stream));
    }

    public long readU32LE(InputStream stream) throws IOException
    {
        return this.readUnsignedInt(stream);
    }

    public int readShort(InputStream stream) throws IOException
    {
        this.readFully(stream, this.buf, 0, 2);

        return b2i(this.buf[0], this.buf[1], (byte) 0, (byte) 0);
    }

    public int readU16LE(InputStream stream) throws IOException
    {
        return this.readShort(stream);
    }

    public void readFully(InputStream stream, byte[] target, int offset, int length) throws IOException
    {
        if (offset < 0 || length < 0 || offset > target.length - length)
        {
            throw new IndexOutOfBoundsException("Invalid read range");
        }

        int read = 0;

        while (read < length)
        {
            int count = stream.read(target, offset + read, length - read);

            if (count < 0)
            {
                throw new EOFException("Unexpected end of stream");
            }

            if (count == 0)
            {
                int value = stream.read();

                if (value < 0)
                {
                    throw new EOFException("Unexpected end of stream");
                }

                target[offset + read] = (byte) value;
                count = 1;
            }

            read += count;
        }
    }

    public void skip(InputStream stream, long bytes) throws IOException
    {
        this.skipFully(stream, bytes);
    }

    public void skipFully(InputStream stream, long bytes) throws IOException
    {
        if (bytes < 0)
        {
            throw new IllegalArgumentException("Skip length cannot be negative");
        }

        while (bytes > 0)
        {
            long skipped = stream.skip(bytes);

            if (skipped > 0)
            {
                bytes -= skipped;
                continue;
            }

            if (stream.read() < 0)
            {
                throw new EOFException("Unexpected end of stream while skipping " + bytes + " byte(s)");
            }

            bytes -= 1;
        }
    }
}
