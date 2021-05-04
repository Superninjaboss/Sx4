package com.sx4.bot.entities.economy.item;

import com.sx4.bot.managers.EconomyManager;
import org.bson.Document;

import java.util.List;
import java.util.stream.Collectors;

public class ItemStack<Type extends Item> implements Comparable<ItemStack<Type>> {
	
	private final Type item;
	private long amount;
	
	@SuppressWarnings("unchecked")
	public ItemStack(EconomyManager manager, Document data) {
		Document item = data.get("item", Document.class);

		ItemType type = ItemType.fromType(data.getInteger("type"));
		Item defaultItem = manager.getItemById(item.getInteger("id"));
		
		this.item = (Type) type.create(item, defaultItem);
		this.amount = data.getLong("amount");
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
		String string = this.getName() + " x" + this.amount;
		if (this.item instanceof Tool) {
			return string + " (" + ((Tool) this.item).getCurrentDurability() + " Durability)";
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
	
}
