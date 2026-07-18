package mchorse.bbs_mod.network;

import mchorse.bbs_mod.data.storage.DataStorage;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Preflights BBS1 data received from the network before the legacy decoder is
 * allowed to allocate collections or primitive arrays. Disk serialization is
 * intentionally unchanged; these limits apply only at the network boundary.
 */
public final class NetworkDataDecoder
{
    static final int MAX_NESTING_DEPTH = 64;
    static final int MAX_KEY_COUNT = 65_536;
    static final int MAX_VALUE_COUNT = 262_144;
    static final int MAX_COLLECTION_ENTRIES = 262_144;

    public static BaseType decode(byte[] bytes)
    {
        try
        {
            Validator validator = new Validator(bytes);

            validator.validate();

            return DataStorage.readFromStream(new ByteArrayInputStream(bytes));
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    public static MapType decodeMap(byte[] bytes)
    {
        BaseType decoded = decode(bytes);

        return decoded instanceof MapType map ? map : null;
    }

    private static final class Validator
    {
        private final byte[] bytes;
        private int cursor;
        private int keyWidth;
        private boolean[] definedKeys;
        private int values;

        private Validator(byte[] bytes) throws IOException
        {
            if (bytes == null || bytes.length == 0 || bytes.length > PacketCrusher.MAX_TRANSFER_BYTES)
            {
                throw new IOException("BBS1 network payload size is invalid");
            }

            this.bytes = bytes;
        }

        private void validate() throws IOException
        {
            this.require(5);

            if (this.readUnsignedByte() != 'B'
                || this.readUnsignedByte() != 'B'
                || this.readUnsignedByte() != 'S'
                || this.readUnsignedByte() != '1')
            {
                throw new IOException("BBS1 network payload has an invalid header");
            }

            int keyType = this.readUnsignedByte();

            if (keyType < 0 || keyType > 2)
            {
                throw new IOException("BBS1 network payload has an invalid key type");
            }

            this.keyWidth = keyType == 0 ? 1 : keyType == 1 ? 2 : 4;

            int keyCount = this.readSizedCount();

            if (keyCount < 0 || keyCount > MAX_KEY_COUNT)
            {
                throw new IOException("BBS1 network key count exceeds the budget");
            }

            this.definedKeys = new boolean[keyCount];

            for (int i = 0; i < keyCount; i++)
            {
                int keyIndex = this.readSizedCount();

                if (keyIndex < 0 || keyIndex >= keyCount || this.definedKeys[keyIndex])
                {
                    throw new IOException("BBS1 network key index is invalid");
                }

                this.definedKeys[keyIndex] = true;
                this.skipUtf();
            }

            this.readValue(0);

            if (this.cursor != this.bytes.length)
            {
                throw new IOException("BBS1 network payload contains trailing bytes");
            }
        }

        private void readValue(int depth) throws IOException
        {
            if (depth > MAX_NESTING_DEPTH)
            {
                throw new IOException("BBS1 network nesting exceeds the budget");
            }

            this.values += 1;

            if (this.values > MAX_VALUE_COUNT)
            {
                throw new IOException("BBS1 network value count exceeds the budget");
            }

            int type = this.readUnsignedByte();

            if (type == BaseType.TYPE_MAP)
            {
                int count = this.readCollectionCount(this.keyWidth + 1);

                for (int i = 0; i < count; i++)
                {
                    int keyIndex = this.readSizedCount();

                    if (keyIndex < 0 || keyIndex >= this.definedKeys.length || !this.definedKeys[keyIndex])
                    {
                        throw new IOException("BBS1 network map references an unknown key");
                    }

                    this.readValue(depth + 1);
                }
            }
            else if (type == BaseType.TYPE_LIST)
            {
                int count = this.readCollectionCount(1);

                for (int i = 0; i < count; i++)
                {
                    this.readValue(depth + 1);
                }
            }
            else if (type == BaseType.TYPE_STRING)
            {
                this.skipUtf();
            }
            else if (type == BaseType.TYPE_BYTE)
            {
                this.skip(1L);
            }
            else if (type == BaseType.TYPE_SHORT)
            {
                this.skip(2L);
            }
            else if (type == BaseType.TYPE_INT || type == BaseType.TYPE_FLOAT)
            {
                this.skip(4L);
            }
            else if (type == BaseType.TYPE_LONG || type == BaseType.TYPE_DOUBLE)
            {
                this.skip(8L);
            }
            else if (type == BaseType.TYPE_BYTE_ARRAY)
            {
                this.skipArray(1);
            }
            else if (type == BaseType.TYPE_SHORT_ARRAY)
            {
                this.skipArray(2);
            }
            else if (type == BaseType.TYPE_INT_ARRAY)
            {
                this.skipArray(4);
            }
            else if (type == BaseType.TYPE_LONG_ARRAY)
            {
                this.skipArray(8);
            }
            else
            {
                throw new IOException("BBS1 network payload contains an unknown value type");
            }
        }

        private int readCollectionCount(int minimumBytesPerEntry) throws IOException
        {
            int count = this.readInt();

            if (count < 0
                || count > MAX_COLLECTION_ENTRIES
                || count > MAX_VALUE_COUNT - this.values
                || (long) count * minimumBytesPerEntry > this.remaining())
            {
                throw new IOException("BBS1 network collection count exceeds the budget");
            }

            return count;
        }

        private void skipArray(int bytesPerElement) throws IOException
        {
            int count = this.readInt();

            if (count < 0 || count > MAX_COLLECTION_ENTRIES)
            {
                throw new IOException("BBS1 network array length exceeds the budget");
            }

            this.skip((long) count * bytesPerElement);
        }

        private void skipUtf() throws IOException
        {
            int length = this.readUnsignedShort();

            this.skip(length);
        }

        private int readSizedCount() throws IOException
        {
            if (this.keyWidth == 1)
            {
                return this.readUnsignedByte();
            }

            if (this.keyWidth == 2)
            {
                return this.readUnsignedShort();
            }

            return this.readInt();
        }

        private int readUnsignedByte() throws IOException
        {
            this.require(1);

            return this.bytes[this.cursor++] & 0xff;
        }

        private int readUnsignedShort() throws IOException
        {
            this.require(2);

            return (this.readUnsignedByte() << 8) | this.readUnsignedByte();
        }

        private int readInt() throws IOException
        {
            this.require(4);

            return (this.readUnsignedByte() << 24)
                | (this.readUnsignedByte() << 16)
                | (this.readUnsignedByte() << 8)
                | this.readUnsignedByte();
        }

        private void skip(long length) throws IOException
        {
            if (length < 0L || length > this.remaining())
            {
                throw new IOException("BBS1 network value length exceeds the remaining payload");
            }

            this.cursor += (int) length;
        }

        private int remaining()
        {
            return this.bytes.length - this.cursor;
        }

        private void require(int length) throws IOException
        {
            if (length < 0 || length > this.remaining())
            {
                throw new IOException("BBS1 network payload is truncated");
            }
        }
    }
}
