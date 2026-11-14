package com.breeze.auction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** config/breeze-auction/config.json */
public final class AuctionConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("breeze-auction");
	private static final Path FILE = DIR.resolve("config.json");
	private static AuctionConfig instance = new AuctionConfig();

	/** Item used to pay for bids, for example minecraft:diamond. */
	public String currencyItem = "minecraft:diamond";
	/** Player names (any case) that may create and cancel auctions. */
	public List<String> admins = new ArrayList<>();
	/** If true every player may create auctions, not only admins. */
	public boolean anyoneCanCreate = false;
	/** Smallest allowed step above the current highest bid. */
	public long minIncrement = 1;
	public long minDurationSeconds = 30;
	public long maxDurationMinutes = 60 * 24 * 14;
	public int maxActivePerSeller = 5;

	public static AuctionConfig get() {
		return instance;
	}

	public static void load() {
		try {
			Files.createDirectories(DIR);
			if (Files.exists(FILE)) {
				AuctionConfig loaded = GSON.fromJson(Files.readString(FILE), AuctionConfig.class);
				if (loaded != null) {
					instance = loaded;
					if (instance.admins == null) {
						instance.admins = new ArrayList<>();
					}
				}
			}
			save(); // writes defaults / newly added fields
		} catch (Exception e) {
			BreezeAuction.LOGGER.warn("Could not load the config, using defaults", e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(DIR);
			Files.writeString(FILE, GSON.toJson(instance));
		} catch (Exception e) {
			BreezeAuction.LOGGER.warn("Could not save the config", e);
		}
	}

	public boolean isAdmin(String name) {
		for (String a : admins) {
			if (a.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}
}
