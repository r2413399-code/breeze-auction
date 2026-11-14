package com.breeze.auction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public final class AuctionCommands {
	private AuctionCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("auction")
				.executes(AuctionCommands::help)
				.then(CommandManager.literal("help").executes(AuctionCommands::help))
				.then(CommandManager.literal("create")
						.then(CommandManager.argument("duration", StringArgumentType.word())
								.then(CommandManager.argument("min", LongArgumentType.longArg(1))
										.executes(ctx -> create(ctx, 0))
										.then(CommandManager.argument("max", LongArgumentType.longArg(1))
												.executes(ctx -> create(ctx, LongArgumentType.getLong(ctx, "max")))))))
				.then(CommandManager.literal("bid")
						.then(CommandManager.argument("amount", LongArgumentType.longArg(1))
								.executes(ctx -> bid(ctx, -1))
								.then(CommandManager.argument("id", IntegerArgumentType.integer(1))
										.executes(ctx -> bid(ctx, IntegerArgumentType.getInteger(ctx, "id"))))))
				.then(CommandManager.literal("list").executes(AuctionCommands::list))
				.then(CommandManager.literal("info")
						.then(CommandManager.argument("id", IntegerArgumentType.integer(1)).executes(AuctionCommands::info)))
				.then(CommandManager.literal("cancel")
						.then(CommandManager.argument("id", IntegerArgumentType.integer(1)).executes(AuctionCommands::cancel)))
				.then(CommandManager.literal("claim").executes(AuctionCommands::claim))
				.then(CommandManager.literal("admin")
						.requires(source -> source.getEntity() == null) // console only
						.then(CommandManager.literal("add")
								.then(CommandManager.argument("name", StringArgumentType.word()).executes(ctx -> admin(ctx, true))))
						.then(CommandManager.literal("remove")
								.then(CommandManager.argument("name", StringArgumentType.word()).executes(ctx -> admin(ctx, false))))));
	}

	private static void send(CommandContext<ServerCommandSource> ctx, Text text) {
		ctx.getSource().sendFeedback(() -> text, false);
	}

	private static int fail(CommandContext<ServerCommandSource> ctx, String message) {
		ctx.getSource().sendError(Text.literal(message));
		return 0;
	}

	private static boolean isAdmin(ServerPlayerEntity player) {
		return AuctionConfig.get().isAdmin(player.getName().getString());
	}

	// ------------------------------------------------------------------ commands
	private static int help(CommandContext<ServerCommandSource> ctx) {
		send(ctx, AuctionManager.prefix().append(Text.literal("Commands").formatted(Formatting.WHITE)));
		String[] lines = {
				"/auction create <time> <minBid> [maxBid]  - auction the item in your hand (time: 30 = minutes, or 90s, 45m, 2h, 3d)",
				"/auction bid <amount> [id]  - highest bid when time runs out wins",
				"/auction list   /auction info <id>",
				"/auction cancel <id>   /auction claim"
		};
		for (String l : lines) {
			send(ctx, Text.literal(l).formatted(Formatting.GRAY));
		}
		return 1;
	}

	private static int create(CommandContext<ServerCommandSource> ctx, long max) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		AuctionConfig cfg = AuctionConfig.get();
		if (!cfg.anyoneCanCreate && !isAdmin(player)) {
			return fail(ctx, "Only auction admins can create auctions.");
		}
		long duration = Util.parseDuration(StringArgumentType.getString(ctx, "duration"));
		if (duration < 0) {
			return fail(ctx, "Time must look like 30 (minutes), 90s, 45m, 2h or 3d.");
		}
		long min = LongArgumentType.getLong(ctx, "min");
		String error = AuctionManager.create(player, duration, min, max);
		return error == null ? 1 : fail(ctx, error);
	}

	private static int bid(CommandContext<ServerCommandSource> ctx, int id) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		long amount = LongArgumentType.getLong(ctx, "amount");
		Auction auction;
		if (id < 0) {
			List<Auction> all = AuctionManager.all();
			if (all.isEmpty()) {
				return fail(ctx, "There are no running auctions.");
			}
			if (all.size() > 1) {
				return fail(ctx, "There are " + all.size() + " auctions - say which one: /auction bid <amount> <id>");
			}
			auction = all.get(0);
		} else {
			auction = AuctionManager.find(id);
			if (auction == null) {
				return fail(ctx, "There is no auction #" + id + ".");
			}
		}
		String error = AuctionManager.bid(player, auction, amount);
		return error == null ? 1 : fail(ctx, error);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		List<Auction> all = AuctionManager.all();
		if (all.isEmpty()) {
			send(ctx, AuctionManager.prefix().append(Text.literal("No running auctions.").formatted(Formatting.WHITE)));
			return 1;
		}
		long now = System.currentTimeMillis();
		for (Auction a : all) {
			Text top = a.topBidder == null
					? Text.literal("no bids yet").formatted(Formatting.GRAY)
					: Text.literal(a.topBidderName + " " + a.topBid).formatted(Formatting.GREEN);
			send(ctx, Text.literal("#" + a.id + " ").formatted(Formatting.YELLOW)
					.append(AuctionManager.itemText(a.item).copy().formatted(Formatting.AQUA))
					.append(Text.literal(" | top: ").formatted(Formatting.WHITE)).append(top)
					.append(Text.literal(" | min " + a.minBid + (a.maxBid == 0 ? "" : ", max " + a.maxBid)
							+ " | " + Util.duration(a.endTime - now) + " left").formatted(Formatting.WHITE)));
		}
		return all.size();
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		Auction a = AuctionManager.find(IntegerArgumentType.getInteger(ctx, "id"));
		if (a == null) {
			return fail(ctx, "There is no such auction.");
		}
		ItemStack stack = a.item;
		send(ctx, Text.literal("Auction #" + a.id).formatted(Formatting.GOLD, Formatting.BOLD));
		send(ctx, Text.literal("Item: ").formatted(Formatting.GRAY).append(AuctionManager.itemText(stack).copy().formatted(Formatting.AQUA)));
		send(ctx, Text.literal("Seller: " + a.sellerName).formatted(Formatting.GRAY));
		send(ctx, Text.literal("Pay with: ").formatted(Formatting.GRAY).append(AuctionManager.money(1, a.currency)));
		send(ctx, Text.literal("Minimum bid: " + a.minBid + "   Maximum bid: " + (a.maxBid == 0 ? "none" : a.maxBid)).formatted(Formatting.GRAY));
		send(ctx, Text.literal("Highest bid: " + (a.topBidder == null ? "none" : a.topBid + " by " + a.topBidderName)).formatted(Formatting.GRAY));
		send(ctx, Text.literal("Time left: " + Util.duration(a.endTime - System.currentTimeMillis())).formatted(Formatting.GRAY));
		return 1;
	}

	private static int cancel(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		Auction a = AuctionManager.find(IntegerArgumentType.getInteger(ctx, "id"));
		if (a == null) {
			return fail(ctx, "There is no such auction.");
		}
		if (!a.seller.equals(player.getUuid()) && !isAdmin(player)) {
			return fail(ctx, "Only the seller or an auction admin can cancel this.");
		}
		AuctionManager.cancel(a);
		return 1;
	}

	private static int claim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		if (!AuctionManager.hasPending(player.getUuid())) {
			return fail(ctx, "You have nothing waiting.");
		}
		int given = AuctionManager.claim(player);
		send(ctx, AuctionManager.prefix().append(Text.literal("Collected " + given + " stack(s)."
				+ (AuctionManager.hasPending(player.getUuid()) ? " Make room and run /auction claim again for the rest." : "")).formatted(Formatting.WHITE)));
		return given;
	}

	/** Console only: /auction admin add <name> */
	private static int admin(CommandContext<ServerCommandSource> ctx, boolean add) {
		String name = StringArgumentType.getString(ctx, "name");
		AuctionConfig cfg = AuctionConfig.get();
		if (add) {
			if (!cfg.isAdmin(name)) {
				cfg.admins.add(name);
			}
		} else {
			cfg.admins.removeIf(a -> a.equalsIgnoreCase(name));
		}
		AuctionConfig.save();
		send(ctx, Text.literal((add ? "Added " : "Removed ") + name + (add ? " to" : " from") + " the auction admins."));
		return 1;
	}
}
