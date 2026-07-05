package mchorse.bbs_mod.data.types;

import mchorse.bbs_mod.data.DataStorageContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.StringJoiner;

public class LongArrayType extends BaseType
{
    public static long[] DEFAULT = new long[0];

    public long[] value = DEFAULT;

    public LongArrayType()
    {}

    public LongArrayType(long[] value)
    {
        this.value = value;
    }

    @Override
    public byte getTypeId()
    {
        return BaseType.TYPE_LONG_ARRAY;
    }

    @Override
    public BaseType copy()
    {
        return new LongArrayType(Arrays.copyOf(this.value, this.value.length));
    }

    @Override
    public void read(DataStorageContext context) throws IOException
    {
        int c = context.in.readInt();
        this.value = new long[c];

        byte[] bytes = new byte[c * 8];
        int counter = 0;

        while (counter < bytes.length)
        {
            counter += context.in.read(bytes, counter, bytes.length - counter);
        }

        for (int i = 0; i < c; i++)
        {
            long value = 0L;

            for (int b = 0; b < 8; b++)
            {
                value |= ((long) (bytes[i * 8 + b] & 0xff)) << (b * 8);
            }

            this.value[i] = value;
        }
    }

    @Override
    public void write(DataStorageContext context) throws IOException
    {
        int c = this.value.length;
        byte[] bytes = new byte[c * 8];

        context.out.writeInt(c);

        for (int i = 0; i < c; i++)
        {
            long value = this.value[i];

            for (int b = 0; b < 8; b++)
            {
                bytes[i * 8 + b] = (byte) ((value >> (b * 8)) & 0xff);
            }
        }

        context.out.write(bytes);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof LongArrayType)
        {
            LongArrayType array = (LongArrayType) obj;

            if (array.value.length != this.value.length)
            {
                return false;
            }

            for (int i = 0; i < this.value.length; i++)
            {
                if (this.value[i] != array.value[i])
                {
                    return false;
                }
            }

            return true;
        }

        return super.equals(obj);
    }

    @Override
    public String toString()
    {
        StringJoiner joiner = new StringJoiner(",");

        for (long value : this.value)
        {
            joiner.add(value + "l");
        }

        return "[l;" + joiner.toString() + "]";
    }
}
