package com.cobblecheesescore.utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Low-level NBT (Named Binary Tag) file reader
 * Parses Minecraft NBT format used for Pokédex data
 */
public class NBTReader {
    
    public static Map<String, Object> readNBTFile(Path nbtFile) throws IOException {
        byte[] data = Files.readAllBytes(nbtFile);
        
        // Try GZIP decompression
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             GZIPInputStream gzis = new GZIPInputStream(bais)) {
            data = gzis.readAllBytes();
        } catch (IOException e) {
            // Not compressed, use as-is
        }
        
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        
        // Read root compound tag
        byte tagType = dis.readByte();
        if (tagType != 10) { // TAG_Compound
            throw new IOException("Invalid NBT file: root tag is not compound");
        }
        
        // Read and skip root name
        short nameLength = dis.readShort();
        dis.skipBytes(nameLength);
        
        // Read root compound
        return readCompound(dis);
    }
    
    private static Map<String, Object> readCompound(DataInputStream dis) throws IOException {
        Map<String, Object> compound = new HashMap<>();
        
        while (true) {
            byte tagType = dis.readByte();
            
            if (tagType == 0) { // TAG_End
                break;
            }
            
            String name = readString(dis);
            Object value = readTag(dis, tagType);
            
            compound.put(name, value);
        }
        
        return compound;
    }
    
    private static Object readTag(DataInputStream dis, byte tagType) throws IOException {
        return switch (tagType) {
            case 1 -> dis.readByte(); // TAG_Byte
            case 2 -> dis.readShort(); // TAG_Short
            case 3 -> dis.readInt(); // TAG_Int
            case 4 -> dis.readLong(); // TAG_Long
            case 5 -> dis.readFloat(); // TAG_Float
            case 6 -> dis.readDouble(); // TAG_Double
            case 7 -> { // TAG_Byte_Array
                int length = dis.readInt();
                byte[] array = new byte[length];
                dis.readFully(array);
                yield array;
            }
            case 8 -> readString(dis); // TAG_String
            case 9 -> readList(dis); // TAG_List
            case 10 -> readCompound(dis); // TAG_Compound
            case 11 -> { // TAG_Int_Array
                int length = dis.readInt();
                int[] array = new int[length];
                for (int i = 0; i < length; i++) {
                    array[i] = dis.readInt();
                }
                yield array;
            }
            case 12 -> { // TAG_Long_Array
                int length = dis.readInt();
                long[] array = new long[length];
                for (int i = 0; i < length; i++) {
                    array[i] = dis.readLong();
                }
                yield array;
            }
            default -> throw new IOException("Unknown tag type: " + tagType);
        };
    }
    
    private static String readString(DataInputStream dis) throws IOException {
        short length = dis.readShort();
        if (length == 0) return "";
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, "UTF-8");
    }
    
    private static List<Object> readList(DataInputStream dis) throws IOException {
        byte elemType = dis.readByte();
        int length = dis.readInt();
        List<Object> list = new ArrayList<>(length);
        
        for (int i = 0; i < length; i++) {
            list.add(readTag(dis, elemType));
        }
        
        return list;
    }
}
