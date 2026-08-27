package com.solidus.util;

import com.solidus.SolidusMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Handles all file I/O operations for Solidus configuration files.
 * Manages loading and saving of shop.json and other configuration files
 * from the server's config directory.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static Path configDir;

    private ConfigManager() {}

    /**
     * Initializes the configuration directory.
     * Must be called during mod initialization before any config operations.
     */
    public static void initialize(Path serverRunDir) {
        configDir = serverRunDir.resolve("config").resolve("solidus");
        try {
            Files.createDirectories(configDir);
            SolidusMod.LOGGER.info("Solidus config directory: {}", configDir.toAbsolutePath());
        } catch (IOException e) {
            SolidusMod.LOGGER.error("Failed to create Solidus config directory!", e);
        }
    }

    /**
     * Gets the configuration directory path.
     * @throws IllegalStateException if called before initialize()
     */
    public static Path getConfigDir() {
        if (configDir == null) {
            throw new IllegalStateException("ConfigManager not initialized! Call initialize() first.");
        }
        return configDir;
    }

    /**
     * Loads a JSON configuration file from the config directory.
     * If the file does not exist, returns null so the caller can create defaults.
     *
     * @param fileName The name of the configuration file (e.g., "shop.json")
     * @return Parsed JsonObject or null if file does not exist
     */
    public static JsonObject loadJson(String fileName) {
        Path filePath = configDir.resolve(fileName);
        if (!Files.exists(filePath)) {
            SolidusMod.LOGGER.info("Config file '{}' not found, will create default.", fileName);
            return null;
        }
        try {
            String content = Files.readString(filePath);
            return GSON.fromJson(content, JsonObject.class);
        } catch (IOException e) {
            SolidusMod.LOGGER.error("Failed to read config file: {}", fileName, e);
            return null;
        } catch (com.google.gson.JsonSyntaxException e) {
            SolidusMod.LOGGER.error("Invalid JSON syntax in config file: {}", fileName, e);
            return null;
        }
    }

    /**
     * Saves a JSON configuration file to the config directory.
     *
     * @param fileName The name of the configuration file
     * @param json     The JsonObject to save
     */
    public static void saveJson(String fileName, JsonObject json) {
        Path filePath = configDir.resolve(fileName);
        try {
            String content = GSON.toJson(json);
            Files.writeString(filePath, content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            SolidusMod.LOGGER.info("Saved config file: {}", fileName);
        } catch (IOException e) {
            SolidusMod.LOGGER.error("Failed to save config file: {}", fileName, e);
        }
    }

    /**
     * Copies a default resource from the mod JAR to the config directory
     * if the config file does not already exist.
     *
     * @param resourceName The resource path within the JAR (e.g., "shop.json")
     * @param fileName     The target file name in the config directory
     */
    public static void copyDefaultIfMissing(String resourceName, String fileName) {
        Path filePath = configDir.resolve(fileName);
        if (Files.exists(filePath)) {
            return;
        }
        try (var is = SolidusMod.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                SolidusMod.LOGGER.warn("Default resource '{}' not found in JAR.", resourceName);
                return;
            }
            String content = new String(is.readAllBytes());
            Files.writeString(filePath, content, StandardOpenOption.CREATE);
            SolidusMod.LOGGER.info("Created default config file: {}", fileName);
        } catch (IOException e) {
            SolidusMod.LOGGER.error("Failed to copy default config: {}", fileName, e);
        }
    }

    /**
     * Reads the entire content of a file in the config directory as a string.
     */
    public static String readFile(String fileName) {
        Path filePath = configDir.resolve(fileName);
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            SolidusMod.LOGGER.error("Failed to read file: {}", fileName, e);
            return null;
        }
    }

    /**
     * Gets the Gson instance used for configuration serialization.
     */
    public static Gson getGson() {
        return GSON;
    }
}
