package mchorse.bbs_mod.network;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ByteArrayType;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.IntArrayType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class NetworkDataDecoderTest
{
    public static void runAll()
    {
        testNormalRoundTrip();
        testDeclaredLengthsCannotExceedRemainingBytes();
        testArrayEntryBudgetBoundary();
        testCollectionAndDepthBudgets();
        testKeyAndTrailingValidation();
        testNestedMapBoundaryUsedByGunProperties();
        testGunPropertiesNetworkRoundTrip();
        testGunPropertiesRejectsMalformedNestedTransform();
    }

    private static void testNormalRoundTrip()
    {
        MapType expected = new MapType();
        ListType nested = new ListType();

        nested.add(new ByteType((byte) 7));
        nested.add(new IntArrayType(new int[]{1, 2, 3, 4}));
        expected.put("nested", nested);
        expected.putString("name", "network-safe");

        BaseType decoded = NetworkDataDecoder.decode(DataStorageUtils.writeToBytes(expected));

        check(expected.equals(decoded), "bounded decoder changed a valid BBS1 payload");
    }

    private static void testDeclaredLengthsCannotExceedRemainingBytes()
    {
        expectRejected(valueWithLength(BaseType.TYPE_BYTE_ARRAY, Integer.MAX_VALUE), "huge byte-array length was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_SHORT_ARRAY, Integer.MAX_VALUE), "huge short-array length was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_INT_ARRAY, Integer.MAX_VALUE), "huge int-array length was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_LONG_ARRAY, Integer.MAX_VALUE), "huge long-array length was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_BYTE_ARRAY, -1), "negative array length was accepted");

        expectRejected(bytes((out) ->
        {
            writeHeader(out, 0, 0);
            out.writeByte(BaseType.TYPE_STRING);
            out.writeShort(65_535);
        }), "truncated modified-UTF length was accepted");
    }

    private static void testArrayEntryBudgetBoundary()
    {
        byte[] boundary = new byte[NetworkDataDecoder.MAX_COLLECTION_ENTRIES];
        BaseType decoded = NetworkDataDecoder.decode(
            DataStorageUtils.writeToBytes(new ByteArrayType(boundary))
        );

        check(decoded instanceof ByteArrayType array && array.value.length == boundary.length,
            "the exact primitive-array entry budget was rejected");

        byte[] overBudget = new byte[NetworkDataDecoder.MAX_COLLECTION_ENTRIES + 1];

        expectRejected(
            DataStorageUtils.writeToBytes(new ByteArrayType(overBudget)),
            "a primitive array one entry above the collection budget was accepted"
        );
    }

    private static void testCollectionAndDepthBudgets()
    {
        expectRejected(valueWithLength(BaseType.TYPE_LIST, Integer.MAX_VALUE), "huge list count was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_MAP, Integer.MAX_VALUE), "huge map count was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_LIST, NetworkDataDecoder.MAX_COLLECTION_ENTRIES + 1),
            "a list one entry above the collection budget was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_MAP, NetworkDataDecoder.MAX_COLLECTION_ENTRIES + 1),
            "a map one entry above the collection budget was accepted");
        expectRejected(valueWithLength(BaseType.TYPE_LIST, -1), "negative list count was accepted");

        expectRejected(bytes((out) ->
        {
            writeHeader(out, 0, 0);
            out.writeByte(BaseType.TYPE_LIST);
            out.writeInt(2);
            writeByteList(out, 131_071);
            writeByteList(out, 131_071);
        }), "nested legal-size lists bypassed the aggregate value budget");

        expectRejected(bytes((out) ->
        {
            writeHeader(out, 0, 0);

            for (int i = 0; i <= NetworkDataDecoder.MAX_NESTING_DEPTH; i++)
            {
                out.writeByte(BaseType.TYPE_LIST);
                out.writeInt(1);
            }

            out.writeByte(BaseType.TYPE_BYTE);
            out.writeByte(1);
        }), "over-deep BBS1 nesting was accepted");
    }

    private static void writeByteList(DataOutputStream out, int count) throws IOException
    {
        out.writeByte(BaseType.TYPE_LIST);
        out.writeInt(count);

        for (int i = 0; i < count; i++)
        {
            out.writeByte(BaseType.TYPE_BYTE);
            out.writeByte(i);
        }
    }

    private static void testKeyAndTrailingValidation()
    {
        expectRejected(bytes((out) ->
        {
            writeHeader(out, 2, Integer.MAX_VALUE);
        }), "huge key-table count was accepted");

        expectRejected(bytes((out) ->
        {
            writeHeader(out, 0, 0);
            out.writeByte(BaseType.TYPE_MAP);
            out.writeInt(1);
            out.writeByte(0);
            out.writeByte(BaseType.TYPE_BYTE);
            out.writeByte(1);
        }), "map entry referencing an unknown key was accepted");

        byte[] valid = DataStorageUtils.writeToBytes(new ByteType((byte) 1));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);

        expectRejected(trailing, "trailing bytes after the root value were accepted");
    }

    private static void testNestedMapBoundaryUsedByGunProperties()
    {
        MapType valid = new MapType();

        valid.putInt("x", 1);

        check(NetworkDataDecoder.decodeMap(DataStorageUtils.writeToBytes(valid)) != null, "valid nested gun transform map was rejected");
        check(NetworkDataDecoder.decodeMap(DataStorageUtils.writeToBytes(new ByteType((byte) 1))) == null, "non-map nested gun transform was accepted");
        check(NetworkDataDecoder.decodeMap(valueWithLength(BaseType.TYPE_INT_ARRAY, Integer.MAX_VALUE)) == null,
            "c13 nested gun transform bypassed bounded array validation");
    }

    private static void testGunPropertiesNetworkRoundTrip()
    {
        GunProperties expected = new GunProperties();

        expected.projectileTransform.translate.set(1.25F, -2.5F, 3.75F);
        expected.projectileTransform.scale.set(0.5F, 1.5F, 2.5F);
        expected.projectileTransform.rotationMode = Transform.RotationMode.QUATERNION;
        expected.projectileTransform.quat.set(0.1F, -0.2F, 0.3F, 0.9F).normalize();
        expected.useTarget = true;
        expected.lifeSpan = 321;
        expected.speed = 4.5F;
        expected.friction = 0.75F;
        expected.gravity = -0.125F;
        expected.yaw = false;
        expected.pitch = true;
        expected.fadeIn = 7;
        expected.fadeOut = 11;
        expected.bounces = 3;
        expected.bounceDamping = 0.625F;
        expected.vanish = false;
        expected.damage = 8.25F;
        expected.knockback = 1.75F;
        expected.collideBlocks = true;
        expected.collideEntities = false;

        FriendlyByteBuf buffer = NetworkCompat.createBuffer();

        try
        {
            expected.toNetwork(buffer);

            GunProperties actual = new GunProperties();

            actual.fromNetwork(buffer);

            check(!buffer.isReadable(), "GunProperties.fromNetwork left bytes from a valid c13 tail unread");
            check(expected.projectileTransform.equals(actual.projectileTransform), "projectile transform changed during the c13 network round-trip");
            check(expected.useTarget == actual.useTarget, "useTarget changed during the c13 network round-trip");
            check(expected.lifeSpan == actual.lifeSpan, "lifeSpan changed during the c13 network round-trip");
            check(Float.compare(expected.speed, actual.speed) == 0, "speed changed during the c13 network round-trip");
            check(Float.compare(expected.friction, actual.friction) == 0, "friction changed during the c13 network round-trip");
            check(Float.compare(expected.gravity, actual.gravity) == 0, "gravity changed during the c13 network round-trip");
            check(expected.yaw == actual.yaw, "yaw changed during the c13 network round-trip");
            check(expected.pitch == actual.pitch, "pitch changed during the c13 network round-trip");
            check(expected.fadeIn == actual.fadeIn, "fadeIn changed during the c13 network round-trip");
            check(expected.fadeOut == actual.fadeOut, "fadeOut changed during the c13 network round-trip");
            check(expected.bounces == actual.bounces, "bounces changed during the c13 network round-trip");
            check(Float.compare(expected.bounceDamping, actual.bounceDamping) == 0, "bounceDamping changed during the c13 network round-trip");
            check(expected.vanish == actual.vanish, "vanish changed during the c13 network round-trip");
            check(Float.compare(expected.damage, actual.damage) == 0, "damage changed during the c13 network round-trip");
            check(Float.compare(expected.knockback, actual.knockback) == 0, "knockback changed during the c13 network round-trip");
            check(expected.collideBlocks == actual.collideBlocks, "collideBlocks changed during the c13 network round-trip");
            check(expected.collideEntities == actual.collideEntities, "collideEntities changed during the c13 network round-trip");
        }
        finally
        {
            buffer.release();
        }
    }

    private static void testGunPropertiesRejectsMalformedNestedTransform()
    {
        byte[] validMap = DataStorageUtils.writeToBytes(new MapType());
        byte[] trailingMap = Arrays.copyOf(validMap, validMap.length + 1);

        trailingMap[trailingMap.length - 1] = 0x55;

        expectGunPropertiesRejected(DataStorageUtils.writeToBytes(new ByteType((byte) 1)),
            "GunProperties.fromNetwork accepted a non-map nested transform");
        expectGunPropertiesRejected(trailingMap,
            "GunProperties.fromNetwork accepted trailing bytes in its nested transform");
        expectGunPropertiesRejected(valueWithLength(BaseType.TYPE_INT_ARRAY, Integer.MAX_VALUE),
            "GunProperties.fromNetwork accepted an unbounded nested array length");
    }

    private static void expectGunPropertiesRejected(byte[] transformBytes, String message)
    {
        FriendlyByteBuf buffer = NetworkCompat.createBuffer();

        try
        {
            buffer.writeByteArray(transformBytes);
            writeGunPropertiesTail(buffer);

            try
            {
                new GunProperties().fromNetwork(buffer);
            }
            catch (RuntimeException e)
            {
                return;
            }

            throw new AssertionError(message);
        }
        finally
        {
            buffer.release();
        }
    }

    private static void writeGunPropertiesTail(FriendlyByteBuf buffer)
    {
        buffer.writeBoolean(true);
        buffer.writeInt(100);
        buffer.writeFloat(1F);
        buffer.writeFloat(0.99F);
        buffer.writeFloat(0.05F);
        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
        buffer.writeInt(0);
        buffer.writeInt(0);
        buffer.writeInt(0);
        buffer.writeFloat(0.5F);
        buffer.writeBoolean(true);
        buffer.writeFloat(0F);
        buffer.writeFloat(0F);
        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
    }

    private static byte[] valueWithLength(byte type, int length)
    {
        return bytes((out) ->
        {
            writeHeader(out, 0, 0);
            out.writeByte(type);
            out.writeInt(length);
        });
    }

    private static void writeHeader(DataOutputStream out, int keyType, int keyCount) throws IOException
    {
        out.writeBytes("BBS1");
        out.writeByte(keyType);

        if (keyType == 0)
        {
            out.writeByte(keyCount);
        }
        else if (keyType == 1)
        {
            out.writeShort(keyCount);
        }
        else
        {
            out.writeInt(keyCount);
        }
    }

    private static byte[] bytes(Writer writer)
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);

            writer.write(out);
            out.flush();

            return bytes.toByteArray();
        }
        catch (IOException e)
        {
            throw new AssertionError("test payload construction failed", e);
        }
    }

    private static void expectRejected(byte[] bytes, String message)
    {
        if (NetworkDataDecoder.decode(bytes) == null)
        {
            return;
        }

        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Writer
    {
        void write(DataOutputStream out) throws IOException;
    }
}
