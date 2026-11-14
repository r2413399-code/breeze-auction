package com.breeze.auction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BreezeAuction implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("BreezeAuction");

	@Override
	public void onInitialize() {
		AuctionConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				AuctionCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(AuctionManager::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AuctionManager.stop());

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 20 == 0) {
				AuctionManager.tick();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			if (AuctionManager.hasPending(player.getUuid())) {
				player.sendMessage(AuctionManager.prefix().append(
						Text.literal("You have items waiting - use /auction claim.").formatted(Formatting.YELLOW)), false);
			}
		});
	}
}
