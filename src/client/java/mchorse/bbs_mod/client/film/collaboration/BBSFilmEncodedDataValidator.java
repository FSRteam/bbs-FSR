package mchorse.bbs_mod.client.film.collaboration;

import mchorse.bbs_mod.data.types.BaseType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Bounded structural preflight before allocating an untrusted BBS1 value tree. */
final class BBSFilmEncodedDataValidator
{
    private static final int MAX_DEPTH = 128;
    private static final int MAX_NODES = 1_000_000;

    private BBSFilmEncodedDataValidator()
    {}

    static boolean isValid(byte[] bytes)
    {
        if (bytes == null || bytes.length < 7)
        {
            return false;
        }

        try
        {
            ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
            DataInputStream input = new DataInputStream(raw);

            if (input.readUnsignedByte() != 'B'
                || input.readUnsignedByte() != 'B'
                || input.readUnsignedByte() != 'S'
                || input.readUnsignedByte() != '1')
            {
                return false;
            }

            int keyType = input.readUnsignedByte();

            if (keyType > 2)
            {
                return false;
            }

            int keyCount = readSized(input, keyType);

            if (keyCount < 0 || keyCount > MAX_NODES)
            {
                return false;
            }

            int minimumKeyBytes = switch (keyType)
            {
                case 0 -> 3;
                case 1 -> 4;
                case 2 -> 6;
                default -> throw new IOException("invalid BBS key type");
            };

            if ((long) keyCount * minimumKeyBytes > raw.available())
            {
                return false;
            }

            boolean[] keyIndexes = new boolean[keyCount];

            for (int i = 0; i < keyCount; i++)
            {
                int index = readSized(input, keyType);

                if (index < 0 || index >= keyCount || keyIndexes[index])
                {
                    return false;
                }

                keyIndexes[index] = true;
                input.readUTF();
            }

            NodeBudget budget = new NodeBudget();

            readType(input, raw, keyType, keyCount, 0, budget);

            return raw.available() == 0;
        }
        catch (IOException | RuntimeException e)
        {
            return false;
        }
    }

    private static void readType(
        DataInputStream input,
        ByteArrayInputStream raw,
        int keyType,
        int keyCount,
        int depth,
        NodeBudget budget
    ) throws IOException
    {
        if (depth > MAX_DEPTH || ++budget.nodes > MAX_NODES)
        {
            throw new IOException("BBS data nesting/node limit exceeded");
        }

        int type = input.readUnsignedByte();

        switch (type)
        {
            case BaseType.TYPE_MAP ->
            {
                int count = readCount(input, raw);

                budget.require(count);

                for (int i = 0; i < count; i++)
                {
                    int key = readSized(input, keyType);

                    if (key < 0 || key >= keyCount)
                    {
                        throw new IOException("invalid BBS map key index");
                    }

                    readType(input, raw, keyType, keyCount, depth + 1, budget);
                }
            }
            case BaseType.TYPE_LIST ->
            {
                int count = readCount(input, raw);

                budget.require(count);

                for (int i = 0; i < count; i++)
                {
                    readType(input, raw, keyType, keyCount, depth + 1, budget);
                }
            }
            case BaseType.TYPE_STRING -> input.readUTF();
            case BaseType.TYPE_BYTE -> input.skipNBytes(1);
            case BaseType.TYPE_SHORT -> input.skipNBytes(2);
            case BaseType.TYPE_INT, BaseType.TYPE_FLOAT -> input.skipNBytes(4);
            case BaseType.TYPE_LONG, BaseType.TYPE_DOUBLE -> input.skipNBytes(8);
            case BaseType.TYPE_BYTE_ARRAY -> skipArray(input, raw, 1);
            case BaseType.TYPE_SHORT_ARRAY -> skipArray(input, raw, 2);
            case BaseType.TYPE_INT_ARRAY -> skipArray(input, raw, 4);
            case BaseType.TYPE_LONG_ARRAY -> skipArray(input, raw, 8);
            default -> throw new IOException("unknown BBS data type");
        }
    }

    private static int readCount(DataInputStream input, ByteArrayInputStream raw) throws IOException
    {
        int count = input.readInt();

        if (count < 0 || count > raw.available())
        {
            throw new IOException("invalid BBS collection count");
        }

        return count;
    }

    private static void skipArray(DataInputStream input, ByteArrayInputStream raw, int width) throws IOException
    {
        int count = input.readInt();
        long bytes = (long) count * width;

        if (count < 0 || bytes > raw.available())
        {
            throw new IOException("invalid BBS array length");
        }

        input.skipNBytes(bytes);
    }

    private static int readSized(DataInputStream input, int keyType) throws IOException
    {
        return switch (keyType)
        {
            case 0 -> input.readUnsignedByte();
            case 1 -> input.readUnsignedShort();
            case 2 -> input.readInt();
            default -> throw new IOException("invalid BBS key type");
        };
    }

    private static final class NodeBudget
    {
        private int nodes;

        private void require(int additional) throws IOException
        {
            if ((long) this.nodes + additional > MAX_NODES)
            {
                throw new IOException("BBS data node limit exceeded");
            }
        }
    }
}
