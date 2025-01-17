package com.sx4.bot.entities.economy.item;

import com.sx4.bot.managers.EconomyManager;
import com.sx4.bot.utility.NumberUtility;
import org.bson.Document;

import java.util.List;
import java.util.stream.Collectors;

public class ItemStack<Type extends Item> implements Comparable<ItemStack<Type>> {
	
	private final Type item;
	private long amount;

	public ItemStack(EconomyManager manager, Document data) {
		this(manager.getItemById(data.getEmbedded(List.of("item", "id"), Integer.class)), data.get("item", Document.class), data.getLong("amount"));
	}

	public ItemStack(Item defaultItem, Document data) {
		this(defaultItem, data.get("item", Document.class), data.getLong("amount"));
	}

	@SuppressWarnings("unchecked")
	public ItemStack(Item defaultItem, Document item, long amount) {
		this.item = (Type) defaultItem.getType().create(item, defaultItem);
		this.amount = amount;
	}

	public ItemStack(Type item, long amount) {
		this.item = item;
		this.amount = amount;
	}
	
	public Type getItem() {
		return this.item;
	}
	
	public String getName() {
		return this.item.getName();
	}
	
	public ItemStack<Type> addAmount(long amount) {
		this.amount += amount;
		
		return this;
	}
	
	public ItemStack<Type> removeAmount(long amount) {
		return this.addAmount(-amount);
	}
	
	public long getAmount() {
		return this.amount;
	}

	public long getTotalPrice() {
		return this.item.getPrice() * this.amount;
	}
	
	public boolean equalsItem(ItemStack<?> itemStack) {
		return this.item.getName().equals(itemStack.getItem().getName());
	}
	
	public ItemStack<Type> combine(ItemStack<?> itemStack) {
		if (!itemStack.equalsItem(this)) {
			throw new IllegalArgumentException("Cannot combine item stacks which contain different items");
		}
		
		return this.addAmount(itemStack.getAmount());
	}
	
	public String toString() {
		String string = String.format("%s x%,d", this.getName(), this.amount);
		if (this.item instanceof Tool) {
			return string + " (" + ((Tool) this.item).getDurability() + " Durability)";
		}

		return string;
	}
	
	public Document toData() {
		return this.item.toData()
			.append("amount", this.amount);
	}
	
	public static List<ItemStack<? extends Item>> fromData(EconomyManager manager, List<Document> data) {
		return data.stream().map(d -> new ItemStack<>(manager, d)).collect(Collectors.toList());
	}

	@Override
	public int compareTo(ItemStack<Type> itemStack) {
		return Long.compare(this.amount, itemStack.getAmount());
	}

	public static ItemStack<?> parse(EconomyManager manager, String content, Class<? extends Item> type) {
		int index = content.indexOf(' ');

		Item item;
		String amountContent = index == -1 ? null : content.substring(0, index);
		if (amountContent == null) {
			item = manager.getItemByQuery(content, type);
		} else if (NumberUtility.isNumberUnsigned(amountContent)) {
			item = manager.getItemByQuery(content.substring(index + 1), type);
		} else if (NumberUtility.isNumberUnsigned(amountContent = content.substring((index = content.lastIndexOf(' ')) + 1))) {
			item = manager.getItemByQuery(content.substring(0, index), type);
		} else {
			amountContent = null;
			item = manager.getItemByQuery(content, type);
		}
		
		if (item == null) {
			return null;
		}

		long amount;
		try {
			amount = amountContent == null ? 1L : Long.parseUnsignedLong(amountContent);
		} catch (NumberFormatException e) {
			amount = 1L;
		}

		return new ItemStack<>(item, amount);
	}
	
}
