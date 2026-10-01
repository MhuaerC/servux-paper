package fi.dy.masa.servux.paper.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Vanilla-NBT equivalent of Servux/MaLiLib's {@code DataByteBufUtils} "Data Tag" network codec:
 * {@code writeInt(length)} followed by a GZIP-compressed, empty-named root compound. Readers fall
 * back to an uncompressed stream on {@link ZipException}, matching the Fabric implementation.
 *
 * @see <a href="https://github.com/MattLavalleeMA/servux/blob/13642bc72085937798c215ec291a1ec6968aef64/src/main/java/fi/dy/masa/servux/util/data/tag/util/DataByteBufUtils.java">DataByteBufUtils.java (Fabric reference)</a>
 */
public final class DataByteBufUtils
{
    /** Mirrors {@code SizeTracker.NETWORK_MAX_BYTES} on the Fabric/MaLiLib side. */
    public static final long NETWORK_MAX_BYTES = 64L * 1024L * 1024L;

    private DataByteBufUtils()
    {
    }

    public static void write(ByteBuf buf, CompoundTag nbt) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt != null ? nbt : new CompoundTag(), bytes);

        buf.writeInt(bytes.size());
        buf.writeBytes(bytes.toByteArray());
    }

    /** Returns an empty compound for an empty ({@code TAG_END}) root, which MiniHUD sends for empty requests. */
    public static CompoundTag read(ByteBuf buf) throws IOException
    {
        ByteBuf slice = buf.readSlice(buf.readInt());
        slice.markReaderIndex();

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteBufInputStream(slice)))))
        {
            return readRoot(in);
        }
        catch (ZipException e)
        {
            slice.resetReaderIndex();

            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(slice)))
            {
                return readRoot(in);
            }
        }
    }

    private static CompoundTag readRoot(DataInput in) throws IOException
    {
        // Data Tags include the root name (two bytes even for an empty name).
        // readAnyTag is the name-less network format and would silently read an empty compound.
        return NbtIo.readUnnamedTag(in, NbtAccounter.create(NETWORK_MAX_BYTES)) instanceof CompoundTag compound ? compound : new CompoundTag();
    }
}
