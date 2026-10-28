package com.cobblecheesescore.data;

import com.cobblecheesescore.utils.NBTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Parses Pokédex NBT files from /world/pokedex/[uuid].nbt
 */
public class PokedexNBTParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    
    @SuppressWarnings("unchecked")
    public static void parsePokedex(Path nbtFile, PlayerData playerData) {
        try {
            Map<String, Object> root = NBTReader.readNBTFile(nbtFile);
            
            // Get speciesRecords
            Object speciesRecordsObj = root.get("speciesRecords");
            if (!(speciesRecordsObj instanceof Map)) {
                LOGGER.warn("No speciesRecords found in {}", nbtFile);
                return;
            }
            
            Map<String, Object> speciesRecords = (Map<String, Object>) speciesRecordsObj;
            
            for (Map.Entry<String, Object> entry : speciesRecords.entrySet()) {
                String speciesName = entry.getKey();
                Object speciesData = entry.getValue();
                
                if (!(speciesData instanceof Map)) continue;
                
                Map<String, Object> speciesMap = (Map<String, Object>) speciesData;
                PlayerData.SpeciesRecord speciesRecord = new PlayerData.SpeciesRecord();
                
                // Get formRecords
                Object formRecordsObj = speciesMap.get("formRecords");
                if (formRecordsObj instanceof Map) {
                    Map<String, Object> formRecords = (Map<String, Object>) formRecordsObj;
                    
                    for (Map.Entry<String, Object> formEntry : formRecords.entrySet()) {
                        String formName = formEntry.getKey();
                        Object formData = formEntry.getValue();
                        
                        if (!(formData instanceof Map)) continue;
                        
                        Map<String, Object> formMap = (Map<String, Object>) formData;
                        PlayerData.FormRecord formRecord = new PlayerData.FormRecord();
                        
                        // Read knowledge (CAUGHT or ENCOUNTERED)
                        formRecord.knowledge = (String) formMap.get("knowledge");
                        
                        // Read genders
                        if (formMap.get("genders") instanceof List) {
                            formRecord.genders = (List<String>) formMap.get("genders");
                        }
                        
                        // Read seenShinyStates
                        if (formMap.get("seenShinyStates") instanceof List) {
                            formRecord.seenShinyStates = (List<String>) formMap.get("seenShinyStates");
                        }
                        
                        speciesRecord.formRecords.put(formName, formRecord);
                    }
                }
                
                playerData.pokedexData.put(speciesName, speciesRecord);
            }
            
            LOGGER.debug("Parsed Pokédex for {}: {} species", playerData.getUuid(), 
                        playerData.pokedexData.size());
            
        } catch (Exception e) {
            LOGGER.error("Failed to parse Pokédex NBT file: {}", nbtFile, e);
        }
    }
}
