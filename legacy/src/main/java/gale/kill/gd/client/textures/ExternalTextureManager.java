/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.textures;

import java.awt.image.BufferedImage;
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

    public static void clearCache() {
        DIMS.clear();
        LOCATIONS.clear();
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
        throw new IllegalStateException("Decompilation failed");
    }

    /*
     * Exception decompiling
     */
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
        throw new IllegalStateException("Decompilation failed");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static TextureDimensions getTextureDimensions(String p, String path) {
        TextureDimensions dims = DIMS.get(path);
        if (dims != null) {
            return dims;
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
            throw new IllegalStateException("Missing GD656 texture: " + path, e);
        }
        DIMS.put(path, dims);
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

