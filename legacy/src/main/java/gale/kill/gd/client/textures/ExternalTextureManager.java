/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.textures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

public final class ExternalTextureManager {
    private static final Map<String, TextureDimensions> DIMS = new ConcurrentHashMap<String, TextureDimensions>();
    private static final Map<String, ResourceLocation> LOCATIONS = new ConcurrentHashMap<String, ResourceLocation>();
    /** 读取失败的贴图 + 失败时刻：避免贴图缺失时每帧都去开一次资源流 */
    private static final Map<String, Long> MISSING_AT = new ConcurrentHashMap<String, Long>();
    private static final long MISSING_RETRY_MS = 5000L;

    public static void clearCache() {
        DIMS.clear();
        LOCATIONS.clear();
        MISSING_AT.clear();
    }

    public static ResourceLocation getTexture(String path) {
        ResourceLocation found = LOCATIONS.get(path);
        if (found != null) {
            return found;
        }
        ResourceLocation location = path.indexOf(58) >= 0 ? new ResourceLocation(path) : new ResourceLocation("gd656killicon", path.startsWith("textures/") || path.startsWith("icon/") ? path : "textures/" + path);
        LOCATIONS.put(path, location);
        return location;
    }

    public static boolean isVanillaTexturePath(String p) {
        return p != null && p.startsWith("minecraft:");
    }

    public static boolean isCustomTextureName(String p) {
        return false;
    }

    public static boolean isOfficialTextureName(String p) {
        return p != null && (p.startsWith("killicon_") || p.startsWith("textures/"));
    }

    private static InputStream open(String path) throws IOException {
        return Minecraft.getMinecraft().getResourceManager().getResource(ExternalTextureManager.getTexture(path)).getInputStream();
    }

    /*
     * Exception decompiling
     */
    public static byte[] readTextureBytes(String preset, String path) throws IOException {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Started 2 blocks at once
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.getStartingBlocks(Op04StructuredStatement.java:412)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:487)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
         *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
         *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
         *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:261)
         *     at org.benf.cfr.reader.Driver.doJar(Driver.java:143)
         *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
         *     at org.benf.cfr.reader.Main.main(Main.java:54)
         */
        // 反编译残留的桩实现（永远抛异常）改回真实读取：按资源包路径取原始字节
        InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(ExternalTextureManager.getTexture(path)).getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
        finally {
            in.close();
        }
    }

    public static BufferedImage readTextureImage(String preset, String path) throws IOException {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [1[TRYBLOCK]], but top level block is 6[TRYBLOCK]
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
         *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
         *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
         *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:261)
         *     at org.benf.cfr.reader.Driver.doJar(Driver.java:143)
         *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
         *     at org.benf.cfr.reader.Main.main(Main.java:54)
         */
        byte[] bytes = ExternalTextureManager.readTextureBytes(preset, path);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unsupported image format: " + path);
        }
        return image;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static TextureDimensions getTextureDimensions(String p, String path) {
        TextureDimensions dims = DIMS.get(path);
        if (dims != null) {
            return dims;
        }
        Long failedAt = MISSING_AT.get(path);
        if (failedAt != null) {
            // 失败冷却期内直接给 0×0，让调用方走 64×64 兜底，不再反复读盘
            if (System.currentTimeMillis() - failedAt < MISSING_RETRY_MS) {
                return new TextureDimensions(0, 0);
            }
            MISSING_AT.remove(path);
        }
        try (InputStream in = ExternalTextureManager.open(path);
             MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(in);){
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException(path);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                dims = new TextureDimensions(reader.getWidth(0), reader.getHeight(0));
            }
            finally {
                reader.dispose();
            }
        }
        catch (IOException e) {
            // 贴图缺失时不能抛异常：这个方法是渲染热路径上的，调用方已经用 0 表示"尺寸未知"
            // 并回退到 64×64；原来直接抛 IllegalStateException 会让整帧 HUD 渲染中断。
            dims = new TextureDimensions(0, 0);
        }
        // 只缓存成功结果：失败（0×0）不缓存，换资源包 / 重载纹理后还能重新探测
        if (dims.width > 0 && dims.height > 0) {
            DIMS.put(path, dims);
        } else {
            MISSING_AT.put(path, System.currentTimeMillis());
        }
        return dims;
    }

    public static final class TextureDimensions {
        public final int width;
        public final int height;

        public TextureDimensions(int w, int h) {
            this.width = w;
            this.height = h;
        }
    }
}

