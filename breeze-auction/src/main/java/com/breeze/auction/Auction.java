package com.breeze.auction;

import net.minecraft.item.ItemStack;

import java.util.UUID;

/** One running auction. The bid money is held by the auction (taken from the bidder when they bid). */
public final class Auction {
	public int id;
	public UUID seller;
	public String sellerName;
	public ItemStack item;
	public long minBid;
	/** 0 = no maximum. A bid equal to the maximum wins the auction immediately. */
	public long maxBid;
	/** Registry id of the payment item, fixed when the auction is created. */
	public String currency;
	public long endTime;
	public UUID topBidder;
	public String topBidderName;
	public long topBid;
	public boolean warned;
}
