package com.sx4.bot.commands.economy;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.command.Command;
import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import com.sx4.bot.annotations.argument.Options;
import com.sx4.bot.annotations.command.BotPermissions;
import com.sx4.bot.annotations.command.CommandId;
import com.sx4.bot.annotations.command.Examples;
import com.sx4.bot.category.ModuleCategory;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.database.mongo.model.Operators;
import com.sx4.bot.entities.argument.Alternative;
import com.sx4.bot.entities.economy.item.*;
import com.sx4.bot.paged.PagedResult;
import com.sx4.bot.utility.EconomyUtility;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.utility.NumberUtility;
import com.sx4.bot.waiter.Waiter;
import com.sx4.bot.waiter.exception.CancelException;
import com.sx4.bot.waiter.exception.TimeoutException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

public class AxeCommand extends Sx4Command {

	public AxeCommand() {
		super("axe", 387);

		super.setDescription("View everything about axes");
		super.setExamples("axe shop", "axe buy", "axe info");
		super.setCategoryAll(ModuleCategory.ECONOMY);
	}

	public void onCommand(Sx4CommandEvent event) {
		event.replyHelp().queue();
	}

	@Command(value="shop", description="View all the axes you are able to buy or craft")
	@CommandId(388)
	@Examples({"axe shop"})
	@BotPermissions(permissions={Permission.MESSAGE_EMBED_LINKS})
	public void shop(Sx4CommandEvent event) {
		List<Axe> axes = event.getBot().getEconomyManager().getItems(Axe.class);

		PagedResult<Axe> paged = new PagedResult<>(event.getBot(), axes)
			.setPerPage(12)
			.setCustomFunction(page -> {
				EmbedBuilder embed = new EmbedBuilder()
					.setAuthor("Axe Shop", null, event.getSelfUser().getEffectiveAvatarUrl())
					.setTitle("Page " + page.getPage() + "/" + page.getMaxPage())
					.setDescription("Axes are a good way to get some wood for crafting")
					.setFooter(PagedResult.DEFAULT_FOOTER_TEXT);

				page.forEach((axe, index) -> {
					List<ItemStack<CraftItem>> items = axe.getCraft();
					String craft = items.isEmpty() ? "None" : items.stream().map(ItemStack::toString).collect(Collectors.joining("\n"));
					embed.addField(axe.getName(), String.format("Price: $%,d\nCraft: %s\nDurability: %,d", axe.getPrice(), craft, axe.getMaxDurability()), true);
				});

				return new MessageBuilder().setEmbed(embed.build()).build();
			});

		paged.execute(event);
	}

	@Command(value="buy", description="Buy a axe from the `axe shop`")
	@CommandId(389)
	@Examples({"axe buy Wooden Axe", "axe buy Wooden"})
	public void buy(Sx4CommandEvent event, @Argument(value="axe", endless=true) Axe axe) {
		event.getMongo().withTransaction(session -> {
			Bson filter = Filters.and(
				Filters.eq("userId", event.getAuthor().getIdLong()),
				Filters.eq("item.type", ItemType.AXE.getId())
			);

			Document axeData = event.getMongo().getItems().find(session, filter).projection(Projections.include("_id")).first();
			if (axeData != null) {
				event.replyFailure("You already own a axe").queue();
				session.abortTransaction();
				return;
			}

			FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE).projection(Projections.include("economy.balance")).upsert(true);

			Document data = event.getMongo().getUsers().findOneAndUpdate(session, Filters.eq("_id", event.getAuthor().getIdLong()), List.of(EconomyUtility.getBalanceUpdate(axe.getPrice())), options);
			if (data == null || data.getEmbedded(List.of("economy", "balance"), 0L) < axe.getPrice()) {
				event.replyFailure("You cannot afford a **" + axe.getName() + "**").queue();
				session.abortTransaction();
				return;
			}

			Document insertData = new Document("userId", event.getAuthor().getIdLong())
				.append("amount", 1L)
				.append("item", axe.toData());

			event.getMongo().insertItem(insertData);
		}).whenComplete((updated, exception) -> {
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			if (updated) {
				event.replySuccess("You just bought a **" + axe.getName() + "**").queue();
			}
		});
	}

	@Command(value="craft", description="Craft a axe from the `axe shop`")
	@CommandId(390)
	@Examples({"axe craft Wooden Axe", "axe craft Wooden"})
	public void craft(Sx4CommandEvent event, @Argument(value="axe", endless=true) Axe axe) {
		event.getMongo().withTransaction(session -> {
			Bson filter = Filters.and(
				Filters.eq("userId", event.getAuthor().getIdLong()),
				Filters.eq("item.type", ItemType.AXE.getId())
			);

			Document rodData = event.getMongo().getItems().find(session, filter).projection(Projections.include("_id")).first();
			if (rodData != null) {
				event.replyFailure("You already own a axe").queue();
				session.abortTransaction();
				return;
			}

			List<ItemStack<CraftItem>> craft = axe.getCraft();
			if (craft.isEmpty()) {
				event.replyFailure("You cannot craft this axe").queue();
				session.abortTransaction();
				return;
			}

			for (ItemStack<CraftItem> stack : craft) {
				CraftItem item = stack.getItem();

				Bson itemFilter = Filters.and(
					Filters.eq("userId", event.getAuthor().getIdLong()),
					Filters.eq("item.id", item.getId())
				);

				List<Bson> update = List.of(Operators.set("amount", Operators.let(new Document("amount", Operators.ifNull("$amount", 0)), Operators.cond(Operators.lte(stack.getAmount(), "$$amount"), Operators.subtract("$$amount", stack.getAmount()), "$$amount"))));

				UpdateResult result = event.getMongo().getItems().updateOne(session, itemFilter, update);
				if (result.getModifiedCount() == 0) {
					event.replyFailure("You do not have `" + stack.getAmount() + " " + item.getName() + "`").queue();
					session.abortTransaction();
					return;
				}
			}

			Document insertData = new Document("userId", event.getAuthor().getIdLong())
				.append("amount", 1L)
				.append("item", axe.toData());

			event.getMongo().getItems().insertOne(session, insertData);
		}).whenComplete((updated, exception) -> {
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			if (updated) {
				event.replySuccess("You just crafted a **" + axe.getName() + "**").queue();
			}
		});
	}

	@Command(value="info", aliases={"information"}, description="View information on a users axe")
	@CommandId(391)
	@Examples({"axe info", "axe info @Shea#6653", "axe info Shea"})
	@BotPermissions(permissions={Permission.MESSAGE_EMBED_LINKS})
	public void info(Sx4CommandEvent event, @Argument(value="user", endless=true, nullDefault=true) Member member) {
		Member effectiveMember = member == null ? event.getMember() : member;
		User user = member == null ? event.getAuthor() : effectiveMember.getUser();

		Bson filter = Filters.and(
			Filters.eq("userId", effectiveMember.getIdLong()),
			Filters.eq("item.type", ItemType.AXE.getId())
		);

		Document data = event.getMongo().getItem(filter, Projections.include("item"));
		if (data == null) {
			event.replyFailure("That user does not have an axe").queue();
			return;
		}

		Axe axe = Axe.fromData(event.getBot().getEconomyManager(), data.get("item", Document.class));

		EmbedBuilder embed = new EmbedBuilder()
			.setAuthor(user.getName() + "'s " + axe.getName(), null, user.getEffectiveAvatarUrl())
			.setColor(effectiveMember.getColorRaw())
			.setThumbnail("https://www.shareicon.net/data/2016/09/02/823994_ax_512x512.png")
			.addField("Durability", axe.getCurrentDurability() + "/" + axe.getMaxDurability(), false)
			.addField("Current Price", String.format("$%,d", axe.getCurrentPrice()), false)
			.addField("Price", String.format("$%,d", axe.getPrice()), false)
			.addField("Max Materials", String.valueOf(axe.getMaxMaterials()), false)
			.addField("Multiplier", NumberUtility.DEFAULT_DECIMAL_FORMAT.format(axe.getMultiplier()), false);

		event.reply(embed.build()).queue();
	}

	@Command(value="repair", description="Repair your current axe with the material it is made from")
	@CommandId(392)
	@Examples({"axe repair 10", "axe repair all"})
	public void repair(Sx4CommandEvent event, @Argument(value="durability") @Options("all") Alternative<Integer> option) {
		Bson filter = Filters.and(
			Filters.eq("userId", event.getAuthor().getIdLong()),
			Filters.eq("item.type", ItemType.AXE.getId())
		);

		Document data = event.getMongo().getItem(filter, Projections.include("item"));
		if (data == null) {
			event.replyFailure("You do not have a axe").queue();
			return;
		}

		Axe axe = Axe.fromData(event.getBot().getEconomyManager(), data.get("item", Document.class));

		CraftItem item = axe.getRepairItem();
		if (item == null) {
			event.replyFailure("That axe is not repairable").queue();
			return;
		}

		int maxDurability = axe.getMaxDurability() - axe.getCurrentDurability();
		if (maxDurability <= 0) {
			event.replyFailure("Your axe is already at full durability").queue();
			return;
		}

		int durability;
		if (option.isAlternative()) {
			durability = maxDurability;
		} else {
			int amount = option.getValue();
			if (amount > maxDurability) {
				event.reply("You can only repair your axe by **" + maxDurability + "** durability :no_entry:").queue();
				return;
			}

			durability = amount;
		}

		int itemCount = (int) Math.ceil((((double) axe.getPrice() / item.getPrice()) / axe.getMaxDurability()) * durability);

		event.reply("It will cost you `" + itemCount + " " + item.getName() + "` to repair your axe by **" + durability + "** durability, are you sure you want to repair it? (Yes or No)").submit().thenCompose($ -> {
			return new Waiter<>(event.getBot(), MessageReceivedEvent.class)
				.setUnique(event.getAuthor().getIdLong(), event.getChannel().getIdLong())
				.setPredicate(e -> e.getMessage().getContentRaw().equalsIgnoreCase("yes"))
				.setOppositeCancelPredicate()
				.setTimeout(60)
				.start();
		}).thenCompose(e -> {
			List<Bson> update = List.of(Operators.set("amount", Operators.let(new Document("amount", Operators.ifNull("$amount", 0)), Operators.cond(Operators.lte(itemCount, "$$amount"), Operators.subtract("$$amount", itemCount), "$$amount"))));
			return event.getMongo().updateItem(Filters.and(Filters.eq("item.id", item.getId()), Filters.eq("userId", event.getAuthor().getIdLong())), update, new UpdateOptions());
		}).thenCompose(result -> {
			if (result.getMatchedCount() == 0 || result.getModifiedCount() == 0) {
				event.replyFailure("You do not have `" + itemCount + " " + item.getName() + "`").queue();
				return CompletableFuture.completedFuture(null);
			}

			List<Bson> update = List.of(Operators.set("item.currentDurability", Operators.cond(Operators.eq("$item.currentDurability", axe.getCurrentDurability()), Operators.add("$item.currentDurability", durability), "$item.currentDurability")));

			return event.getMongo().updateItem(Filters.and(Filters.eq("item.id", axe.getId()), Filters.eq("userId", event.getAuthor().getIdLong())), update, new UpdateOptions());
		}).whenComplete((result, exception) -> {
			Throwable cause = exception instanceof CompletionException ? exception.getCause() : exception;
			if (cause instanceof CancelException) {
				event.replySuccess("Cancelled").queue();
				return;
			} else if (cause instanceof TimeoutException) {
				event.reply("Timed out :stopwatch:").queue();
				return;
			}

			if (ExceptionUtility.sendExceptionally(event, exception) || result == null) {
				return;
			}

			if (result.getMatchedCount() == 0) {
				event.replyFailure("You no longer have that axe").queue();
				return;
			}

			if (result.getMatchedCount() == 0) {
				event.replyFailure("The durability of your axe has changed").queue();
				return;
			}

			event.replySuccess("You just repaired your axe by **" + durability + "** durability").queue();
		});
	}

}

