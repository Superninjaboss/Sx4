package com.sx4.bot.commands.mod.auto;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.command.Command;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.*;
import com.sx4.bot.annotations.argument.AlternativeOptions;
import com.sx4.bot.annotations.argument.Limit;
import com.sx4.bot.annotations.command.AuthorPermissions;
import com.sx4.bot.annotations.command.BotPermissions;
import com.sx4.bot.annotations.command.CommandId;
import com.sx4.bot.annotations.command.Examples;
import com.sx4.bot.category.ModuleCategory;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.database.mongo.model.Operators;
import com.sx4.bot.entities.argument.Alternative;
import com.sx4.bot.entities.argument.TimedArgument;
import com.sx4.bot.entities.management.WhitelistType;
import com.sx4.bot.entities.mod.action.ModAction;
import com.sx4.bot.entities.mod.auto.MatchAction;
import com.sx4.bot.entities.mod.auto.RegexType;
import com.sx4.bot.entities.settings.HolderType;
import com.sx4.bot.formatter.FormatterManager;
import com.sx4.bot.formatter.function.FormatterVariable;
import com.sx4.bot.paged.PagedResult;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.utility.TimeUtility;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

public class AntiRegexCommand extends Sx4Command {

	public AntiRegexCommand() {
		super("anti regex", 105);
		
		super.setAliases("antiregex");
		super.setDescription("Setup a regex which if matched will perform an action, use https://regex101.com/ for testing and select Java 8");
		super.setExamples("anti regex add", "anti regex remove", "anti regex list");
		super.setCategoryAll(ModuleCategory.AUTO_MODERATION);
	}
	
	public void onCommand(Sx4CommandEvent event) {
		event.replyHelp().queue();
	}
	
	@Command(value="add", description="Add a regex from `anti regex template list` to be checked on every message")
	@CommandId(106)
	@Examples({"anti regex add 5f023782ef9eba03390a740c"})
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	public void add(Sx4CommandEvent event, @Argument(value="id") ObjectId id) {
		Document regex = event.getMongo().getRegexTemplateById(id, Projections.include("pattern", "title", "type"));
		if (regex == null) {
			event.replyFailure("I could not find that regex template").queue();
			return;
		}

		List<Bson> guildPipeline = List.of(
			Aggregates.project(Projections.fields(Projections.computed("premium", Operators.lt(Operators.nowEpochSecond(), Operators.ifNull("$premium.endAt", 0L))), Projections.computed("guildId", "$_id"))),
			Aggregates.match(Filters.eq("guildId", event.getGuild().getIdLong()))
		);

		List<Bson> pipeline = List.of(
			Aggregates.match(Filters.and(Filters.eq("guildId", event.getGuild().getIdLong()), Filters.exists("enabled", false), Filters.eq("type", RegexType.REGEX.getId()))),
			Aggregates.group(null, Accumulators.sum("count", 1)),
			Aggregates.limit(10),
			Aggregates.unionWith("guilds", guildPipeline),
			Aggregates.group(null, Accumulators.max("count", "$count"), Accumulators.max("premium", "$premium")),
			Aggregates.project(Projections.fields(Projections.computed("premium", Operators.ifNull("$premium", false)), Projections.computed("count", Operators.ifNull("$count", 0))))
		);

		event.getMongo().aggregateRegexes(pipeline).thenCompose(documents -> {
			Document counter = documents.isEmpty() ? null : documents.get(0);

			int count = counter == null ? 0 : counter.getInteger("count");
			if (count >= 3 && !counter.getBoolean("premium")) {
				throw new IllegalArgumentException("You need to have Sx4 premium to have more than 3 enabled anti regexes, you can get premium at <https://www.patreon.com/Sx4>");
			}

			if (count == 10) {
				throw new IllegalArgumentException("You cannot have any more than 10 anti regexes");
			}

			Document pattern = new Document("regexId", id)
				.append("guildId", event.getGuild().getIdLong())
				.append("type", regex.getInteger("type", RegexType.REGEX.getId()))
				.append("pattern", regex.getString("pattern"));

			return event.getMongo().insertRegex(pattern);
		}).thenCompose(result -> {
			event.replySuccess("The regex `" + result.getInsertedId().asObjectId().getValue().toHexString() + "` is now active").queue();

			return event.getMongo().updateRegexTemplateById(id, Updates.inc("uses", 1L));
		}).whenComplete((result, exception) -> {
			Throwable cause = exception instanceof CompletionException ? exception.getCause() : exception;
			if (cause instanceof MongoWriteException && ((MongoWriteException) cause).getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
				event.replyFailure("You already have that anti regex setup in this server").queue();
				return;
			} else if (cause instanceof IllegalArgumentException) {
				event.replyFailure(cause.getMessage()).queue();
				return;
			}

			ExceptionUtility.sendExceptionally(event, exception);
		});
	}

	@Command(value="add", description="Add a custom regex to be checked on every message")
	@CommandId(125)
	@Examples({"anti regex add [0-9]+", "anti regex add https://discord\\.com/channels/([0-9]+)/([0-9]+)/?"})
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	public void add(Sx4CommandEvent event, @Argument(value="regex", endless=true) Pattern pattern) {
		List<Bson> guildPipeline = List.of(
			Aggregates.project(Projections.fields(Projections.computed("premium", Operators.lt(Operators.nowEpochSecond(), Operators.ifNull("$premium.endAt", 0L))), Projections.computed("guildId", "$_id"))),
			Aggregates.match(Filters.eq("guildId", event.getGuild().getIdLong()))
		);

		List<Bson> pipeline = List.of(
			Aggregates.match(Filters.and(Filters.eq("guildId", event.getGuild().getIdLong()), Filters.exists("enabled", false), Filters.eq("type", RegexType.REGEX.getId()))),
			Aggregates.group(null, Accumulators.sum("count", 1)),
			Aggregates.limit(10),
			Aggregates.unionWith("guilds", guildPipeline),
			Aggregates.group(null, Accumulators.max("count", "$count"), Accumulators.max("premium", "$premium")),
			Aggregates.project(Projections.fields(Projections.computed("premium", Operators.ifNull("$premium", false)), Projections.computed("count", Operators.ifNull("$count", 0))))
		);

		event.getMongo().aggregateRegexes(pipeline).thenCompose(documents -> {
			Document counter = documents.isEmpty() ? null : documents.get(0);

			int count = counter == null ? 0 : counter.getInteger("count");
			if (count >= 3 && !counter.getBoolean("premium")) {
				throw new IllegalArgumentException("You need to have Sx4 premium to have more than 3 enabled anti regexes, you can get premium at <https://www.patreon.com/Sx4>");
			}

			if (count == 10) {
				throw new IllegalArgumentException("You cannot have any more than 10 anti regexes");
			}

			Document patternData = new Document("guildId", event.getGuild().getIdLong())
				.append("type", RegexType.REGEX.getId())
				.append("pattern", pattern.pattern());

			return event.getMongo().insertRegex(patternData);
		}).whenComplete((result, exception) -> {
			Throwable cause = exception instanceof CompletionException ? exception.getCause() : exception;
			if (cause instanceof MongoWriteException && ((MongoWriteException) cause).getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
				event.replyFailure("You already have that anti regex setup in this server").queue();
				return;
			} else if (cause instanceof IllegalArgumentException) {
				event.replyFailure(cause.getMessage()).queue();
				return;
			}

			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			event.replySuccess("The regex `" + result.getInsertedId().asObjectId().getValue().toHexString() + "` is now active").queue();
		});
	}

	@Command(value="toggle", aliases={"enable", "disable"}, description="Toggles the state of an anti regex")
	@CommandId(126)
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	@Examples({"anti regex toggle 5f023782ef9eba03390a740c"})
	public void toggle(Sx4CommandEvent event, @Argument(value="id") ObjectId id) {
		List<Bson> guildPipeline = List.of(
			Aggregates.project(Projections.fields(Projections.computed("premium", Operators.lt(Operators.nowEpochSecond(), Operators.ifNull("$premium.endAt", 0L))), Projections.computed("guildId", "$_id"))),
			Aggregates.match(Filters.eq("guildId", event.getGuild().getIdLong()))
		);

		List<Bson> pipeline = List.of(
			Aggregates.match(Filters.and(Filters.eq("guildId", event.getGuild().getIdLong()), Filters.exists("enabled", false), Filters.eq("type", RegexType.REGEX.getId()))),
			Aggregates.project(Projections.include("_id")),
			Aggregates.group(null, Accumulators.push("regexes", Operators.ROOT)),
			Aggregates.unionWith("guilds", guildPipeline),
			Aggregates.group(null, Accumulators.max("regexes", "$regexes"), Accumulators.max("premium", "$premium")),
			Aggregates.project(Projections.fields(Projections.computed("premium", Operators.ifNull("$premium", false)), Projections.computed("count", Operators.size(Operators.ifNull("$regexes", Collections.EMPTY_LIST))), Projections.computed("disabled", Operators.isEmpty(Operators.filter(Operators.ifNull("$regexes", Collections.EMPTY_LIST), Operators.eq("$$this._id", id))))))
		);

		event.getMongo().aggregateRegexes(pipeline).thenCompose(documents -> {
			Document data = documents.isEmpty() ? null : documents.get(0);

			boolean disabled = data == null || data.getBoolean("disabled");
			int count = data == null ? 0 : data.getInteger("count");
			if (data != null && disabled && count >= 3 && !data.getBoolean("premium")) {
				throw new IllegalArgumentException("You need to have Sx4 premium to have more than 3 enabled anti regexes, you can get premium at <https://www.patreon.com/Sx4>");
			}

			if (count >= 10) {
				throw new IllegalArgumentException("You can not have any more than 10 enabled anti regexes");
			}

			List<Bson> update = List.of(Operators.set("enabled", Operators.cond(Operators.exists("$enabled"), Operators.REMOVE, false)));
			FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).projection(Projections.include("enabled"));

			return event.getMongo().findAndUpdateRegex(Filters.eq("_id", id), update, options);
		}).whenComplete((data, exception) -> {
			Throwable cause = exception instanceof CompletionException ? exception.getCause() : exception;
			if (cause instanceof IllegalArgumentException) {
				event.replyFailure(cause.getMessage()).queue();
				return;
			}

			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			event.replySuccess("The anti regex `" + id.toHexString() + "` is now **" + (data.get("enabled", true) ? "enabled" : "disabled") + "**").queue();
		});
	}

	@Command(value="remove", description="Removes an anti regex that you have setup")
	@CommandId(107)
	@Examples({"anti regex remove 5f023782ef9eba03390a740c"})
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	public void remove(Sx4CommandEvent event, @Argument(value="id") ObjectId id) {
		FindOneAndDeleteOptions options = new FindOneAndDeleteOptions().projection(Projections.include("regexId"));

		event.getMongo().findAndDeleteRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), options).thenCompose(data -> {
			if (data == null) {
				throw new IllegalArgumentException("You do that have that regex setup in this server");
			}

			if (data.containsKey("regexId")) {
				return event.getMongo().updateRegexTemplateById(id, Updates.inc("uses", -1L));
			} else {
				return CompletableFuture.completedFuture(null);
			}
		}).whenComplete((result, exception) -> {
			Throwable cause = exception instanceof CompletionException ? exception.getCause() : exception;
			if (cause instanceof IllegalArgumentException) {
				event.replyFailure(cause.getMessage()).queue();
				return;
			}

			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			event.replySuccess("That regex is no longer active").queue();
		});
	}

	@Command(value="set", description="Sets the amount of attempts a user has for an anti regex")
	@CommandId(460)
	@Examples({"anti regex set 5f023782ef9eba03390a740c @Shea#6653 0", "anti regex set 5f023782ef9eba03390a740c Shea 3", "anti regex set 5f023782ef9eba03390a740c 402557516728369153 2"})
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	public void set(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="user") Member member, @Argument(value="attempts") int attempts) {
		Bson filter = Filters.and(Filters.eq("regexId", id), Filters.eq("userId", member.getIdLong()), Filters.eq("guildId", event.getGuild().getIdLong()));

		CompletableFuture<Document> future;
		if (attempts == 0) {
			future = event.getMongo().findAndDeleteRegexAttempt(filter);
		} else {
			FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().projection(Projections.include("attempts")).returnDocument(ReturnDocument.BEFORE).upsert(true);
			future = event.getMongo().findAndUpdateRegexAttempt(filter, Updates.set("attempts", attempts), options);
		}

		future.whenComplete((data, exception) -> {
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			if (data == null) {
				event.replyFailure("I could not find that anti regex").queue();
				return;
			}

			if (data.getInteger("attempts") == attempts) {
				event.replyFailure("That users attempts were already set to that").queue();
				return;
			}

			if (attempts == 0) {
				event.getBot().getAntiRegexManager().clearAttempts(id, member.getIdLong());
			} else {
				event.getBot().getAntiRegexManager().setAttempts(id, member.getIdLong(), attempts);
			}

			event.replySuccess("**" + member.getUser().getAsTag() + "** has had their attempts set to **" + attempts + "**").queue();
		});
	}

	@Command(value="attempts", description="Sets the amount of attempts needed for the mod action to execute")
	@CommandId(108)
	@Examples({"anti regex attempts 5f023782ef9eba03390a740c 3", "anti regex attempts 5f023782ef9eba03390a740c 1"})
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	public void attempts(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="attempts") @Limit(min=1) int attempts) {
		event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), Updates.set("attempts.amount", attempts)).whenComplete((result, exception) -> {
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			if (result.getMatchedCount() == 0) {
				event.replyFailure("I could not find that anti regex").queue();
				return;
			}

			if (result.getModifiedCount() == 0) {
				event.replyFailure("Your attempts where already set to that").queue();
				return;
			}

			event.replySuccess("Attempts to a mod action have been set to **" + attempts + "**").queue();
		});
	}

	@Command(value="admin toggle", aliases={"admin"}, description="Toggles whether administrators should be whitelisted from sending a specific regex or not")
	@CommandId(464)
	@Examples({"anti regex admin toggle 5f023782ef9eba03390a740c"})
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	public void adminToggle(Sx4CommandEvent event, @Argument(value="id") ObjectId id) {
		List<Bson> update = List.of(Operators.set("admin", Operators.cond(Operators.exists("$admin"), Operators.REMOVE, false)));
		FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).projection(Projections.include("admin"));

		event.getMongo().findAndUpdateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update, options).whenComplete((data, exception) -> {
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			if (data == null) {
				event.replyFailure("I could not find that anti regex").queue();
				return;
			}

			event.replySuccess("Administrators are " + (data.getBoolean("admin", true) ? "now" : "no longer") + " whitelisted from sending content matching that regex").queue();
		});
	}

	@Command(value="reset after", description="The time it should take for attempts to be taken away")
	@CommandId(109)
	@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
	@Examples({"anti regex reset after 5f023782ef9eba03390a740c 1 1 day", "anti regex reset after 5f023782ef9eba03390a740c 3 5h 20s", "anti regex reset after 5f023782ef9eba03390a740c 3 5h 20s"})
	public void resetAfter(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="amount") @Limit(min=0) int amount, @Argument(value="time", endless=true, nullDefault=true) Duration time) {
		if (time != null && time.toMinutes() < 5) {
			event.replyFailure("The duration has to be 5 minutes or above").queue();
			return;
		}

		if (amount != 0 && time == null) {
			event.reply("You need to provide a duration if attempts is more than 0").queue();
			return;
		}

		Bson update = amount == 0 ? Updates.unset("attempts.reset") : Updates.set("attempts.reset", new Document("amount", amount).append("after", time.toSeconds()));
		event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update).whenComplete((result, exception) -> {
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			if (result.getMatchedCount() == 0) {
				event.replyFailure("I could not find that anti regex").queue();
				return;
			}

			if (result.getModifiedCount() == 0) {
				event.replyFailure("Your reset attempts configuration was already set to that").queue();
				return;
			}

			event.reply(amount == 0 ? "Users attempts will no longer reset" + event.getConfig().getSuccessEmote() : String.format("Users attempts will now reset **%d** time%s after `%s` %s", amount, amount == 1 ? "" : "s", TimeUtility.LONG_TIME_FORMATTER.parse(time.toSeconds()), event.getConfig().getSuccessEmote())).queue();
		});
	}

	@Command(value="formatters", aliases={"format", "formatting"}, description="Get all the formatters for anti regex you can use")
	@CommandId(468)
	@Examples({"anti regex formatters"})
	@BotPermissions(permissions={Permission.MESSAGE_EMBED_LINKS})
	public void formatters(Sx4CommandEvent event) {
		EmbedBuilder embed = new EmbedBuilder()
			.setAuthor("Anti-Regex Formatters", null, event.getSelfUser().getEffectiveAvatarUrl());

		FormatterManager manager = FormatterManager.getDefaultManager();

		StringJoiner content = new StringJoiner("\n");
		for (FormatterVariable<?> variable : manager.getVariables(User.class)) {
			content.add("`{user." + variable.getName() + "}` - " + variable.getDescription());
		}

		for (FormatterVariable<?> variable : manager.getVariables(TextChannel.class)) {
			content.add("`{channel." + variable.getName() + "}` - " + variable.getDescription());
		}

		content.add("`{regex.id}` - Gets the id of the regex");
		content.add("`{regex.action.name}` - Gets the mod action name if one is set");
		content.add("`{regex.action.exists}` - Returns true or false if an action exists");
		content.add("`{regex.attempts.current}` - Gets the current attempts for the user");
		content.add("`{regex.attempts.max}` - Gets the max attempts set for the anti regex");

		embed.setDescription(content.toString());

		event.reply(embed.build()).queue();
	}

	@Command(value="list", description="Lists the regexes which are active in this server")
	@CommandId(110)
	@Examples({"anti regex list"})
	public void list(Sx4CommandEvent event) {
		List<Document> regexes = event.getMongo().getRegexes(Filters.eq("guildId", event.getGuild().getIdLong()), Projections.include("pattern")).into(new ArrayList<>());
		if (regexes.isEmpty()) {
			event.replyFailure("There are no regexes setup in this server").queue();
			return;
		}

		PagedResult<Document> paged = new PagedResult<>(event.getBot(), regexes)
			.setPerPage(6)
			.setCustomFunction(page -> {
				MessageBuilder builder = new MessageBuilder();

				EmbedBuilder embed = new EmbedBuilder();
				embed.setAuthor("Anti Regex", null, event.getGuild().getIconUrl());
				embed.setTitle("Page " + page.getPage() + "/" + page.getMaxPage());
				embed.setFooter(PagedResult.DEFAULT_FOOTER_TEXT, null);

				page.forEach((data, index) -> embed.addField(data.getObjectId("_id").toHexString(), "`" + data.getString("pattern") + "`", true));

				return builder.setEmbeds(embed.build());
			});

		paged.execute(event);
	}

	public static class ModCommand extends Sx4Command {

		public ModCommand() {
			super("mod", 111);

			super.setDescription("Set specific things to happen when someone reaches a certain amount of attempts");
			super.setExamples("anti regex mod message", "anti regex mod action");
			super.setCategoryAll(ModuleCategory.AUTO_MODERATION);
		}

		public void onCommand(Sx4CommandEvent event) {
			event.replyHelp().queue();
		}

		@Command(value="message", description="Changes the message which is sent when someone hits the max attempts")
		@CommandId(231)
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		@Examples({"anti regex mod message 5f023782ef9eba03390a740c A user has been banned for sending links", "anti regex match message 5f023782ef9eba03390a740c {user.name} has received a {regex.action}"})
		public void message(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="message", endless=true) @Limit(max=1500) String message) {
			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), Updates.set("mod.message", message)).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure("Your mod message for that regex was already set to that").queue();
					return;
				}

				event.replySuccess("Your mod message for that regex has been updated").queue();
			});
		}

		@Command(value="action", description="Sets the action to be taken when a user hits the max attempts")
		@CommandId(112)
		@Examples({"anti regex mod action 5f023782ef9eba03390a740c WARN", "anti regex mod action 5f023782ef9eba03390a740c MUTE 60m", "anti regex mod action 5f023782ef9eba03390a740c none"})
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		public void action(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="action", endless=true) @AlternativeOptions({"none", "reset", "unset"}) Alternative<TimedArgument<ModAction>> option) {
			Bson update;
			if (option.isAlternative()) {
				update = Updates.unset("mod.action");
			} else {
				TimedArgument<ModAction> timedAction = option.getValue();
				ModAction action = timedAction.getArgument();
				if (!action.isOffence()) {
					event.replyFailure("The action has to be an offence").queue();
					return;
				}

				Document modAction = new Document("type", action.getType());

				if (action.isTimed()) {
					Duration duration = timedAction.getDuration();
					if (duration == null) {
						event.replyFailure("You need to provide a duration for this mod action").queue();
						return;
					}

					modAction.append("duration", duration.toSeconds());
				}

				update = Updates.set("mod.action", modAction);
			}

			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure("Your mod action for this regex is already set to that").queue();
					return;
				}

				event.replySuccess("Your mod action for that regex has been updated").queue();
			});
		}

	}

	public static class MatchCommand extends Sx4Command {

		public MatchCommand() {
			super("match", 113);

			super.setDescription("Set specific things to happen when a message is matched with a specific regex");
			super.setExamples("anti regex match action", "anti regex match message");
			super.setCategoryAll(ModuleCategory.AUTO_MODERATION);
		}

		public void onCommand(Sx4CommandEvent event) {
			event.replyHelp().queue();
		}

		@Command(value="message", description="Changes the message which is sent when someone triggers an anti regex")
		@CommandId(114)
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		@Examples({"anti regex match message 5f023782ef9eba03390a740c You cannot have a url in your message :no_entry:", "anti regex match message 5f023782ef9eba03390a740c {user.mention}, don't send that here or else you'll get a {regex.action} :no_entry:"})
		public void message(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="message", endless=true) @Limit(max=1500) String message) {
			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), Updates.set("match.message", message)).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure("Your match message for that regex was already set to that").queue();
					return;
				}

				event.replySuccess("Your match message for that regex has been updated").queue();
			});
		}

		@Command(value="action", description="Set what the bot should do when the regex is matched")
		@CommandId(115)
		@Examples({"anti regex match action 5f023782ef9eba03390a740c SEND_MESSAGE", "anti regex match action 5f023782ef9eba03390a740c SEND_MESSAGE DELETE_MESSAGE"})
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		public void action(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="actions") MatchAction... actions) {
			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), Updates.set("match.action", MatchAction.getRaw(actions))).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure("Your match action for this regex is already set to that").queue();
					return;
				}

				event.replySuccess("Your match action for that regex has been updated").queue();
			});
		}

	}

	public static class WhitelistCommand extends Sx4Command {

		public WhitelistCommand() {
			super("whitelist", 116);

			super.setDescription("Whitelist roles and users from certain channels so they can ignore the anti regex");
			super.setExamples("anti regex whitelist add", "anti regex whitelist remove");
			super.setCategoryAll(ModuleCategory.AUTO_MODERATION);
		}

		public void onCommand(Sx4CommandEvent event) {
			event.replyHelp().queue();
		}

		@Command(value="add", description="Adds a whitelist for a group in the regex")
		@CommandId(117)
		@Examples({"anti regex whitelist add 5f023782ef9eba03390a740c #youtube-links 2 youtube.com", "anti regex whitelist add 5f023782ef9eba03390a740c 0 https://youtube.com"})
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		public void add(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="channel", nullDefault=true) TextChannel channelArgument, @Argument(value="group") @Limit(min=0) int group, @Argument(value="string", endless=true) @Limit(max=250) String string) {
			Document regex = event.getMongo().getRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), Projections.include("pattern", "type"));
			if (regex != null && Pattern.compile(regex.getString("pattern")).matcher("").groupCount() < group) {
				event.replyFailure("There is not a group " + group + " in that regex").queue();
				return;
			}

			List<TextChannel> channels = channelArgument == null ? event.getGuild().getTextChannels() : List.of(channelArgument);

			Bson channelMap = Operators.ifNull("$whitelist", Collections.EMPTY_LIST);

			Document groupData = new Document("group", group).append("strings", List.of(string));

			List<Bson> concat = new ArrayList<>();
			List<Long> channelIds = new ArrayList<>();
			for (TextChannel channel : channels) {
				long channelId = channel.getIdLong();
				channelIds.add(channelId);

				Document channelData = new Document("id", channelId).append("type", WhitelistType.CHANNEL.getId()).append("groups", List.of(groupData));

				Bson channelFilter = Operators.filter(channelMap, Operators.eq("$$this.id", channelId));
				Bson groupMap = Operators.ifNull(Operators.first(Operators.map(channelFilter, "$$this.groups")), Collections.EMPTY_LIST);
				Bson groupFilter = Operators.filter(groupMap, Operators.eq("$$this.group", group));

				concat.add(Operators.cond(Operators.isEmpty(channelFilter), List.of(channelData), Operators.cond(Operators.isEmpty(groupFilter), List.of(Operators.mergeObjects(Operators.first(channelFilter), new Document("groups", Operators.concatArrays(List.of(groupData), Operators.filter(groupMap, Operators.ne("$$this.group", group)))))), List.of(Operators.mergeObjects(Operators.first(channelFilter), new Document("groups", Operators.concatArrays(List.of(Operators.mergeObjects(Operators.first(groupFilter), new Document("strings", Operators.concatArrays(Operators.filter(Operators.first(Operators.map(groupFilter, "$$this.strings")), Operators.ne("$$this", string)), List.of(string))))), Operators.filter(groupMap, Operators.ne("$$this.group", group)))))))));
			}

			concat.add(Operators.filter(channelMap, Operators.not(Operators.in("$$this.id", channelIds))));
			List<Bson> update = List.of(Operators.set("whitelist", Operators.concatArrays(concat)));

			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}


				if (result.getModifiedCount() == 0) {
					event.replyFailure("Group **" + group + "** is already whitelisted from that string in all of the provided channels").queue();
					return;
				}

				event.replySuccess("Group **" + group + "** is now whitelisted from that string in the provided channels").queue();
			});
		}

		@Command(value="add", description="Adds a whitelist for a role or user")
		@CommandId(118)
		@Examples({"anti regex whitelist add 5f023782ef9eba03390a740c #channel @everyone", "anti regex whitelist add 5f023782ef9eba03390a740c @Shea#6653"})
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		public void add(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="channel", nullDefault=true) TextChannel channelArgument, @Argument(value="user | role", endless=true) IPermissionHolder holder) {
			List<TextChannel> channels = channelArgument == null ? event.getGuild().getTextChannels() : List.of(channelArgument);

			Bson channelMap = Operators.ifNull("$whitelist", Collections.EMPTY_LIST);

			boolean role = holder instanceof Role;
			long holderId = holder.getIdLong();

			Document holderData = new Document("id", holderId).append("type", role ? HolderType.ROLE.getType() : HolderType.USER.getType());

			List<Bson> concat = new ArrayList<>();
			List<Long> channelIds = new ArrayList<>();
			for (TextChannel channel : channels) {
				long channelId = channel.getIdLong();
				channelIds.add(channelId);

				Document channelData = new Document("id", channelId).append("type", WhitelistType.CHANNEL.getId()).append("holders", List.of(holderData));

				Bson channelFilter = Operators.filter(channelMap, Operators.eq("$$this.id", channelId));
				concat.add(Operators.cond(Operators.isEmpty(channelFilter), List.of(channelData), List.of(Operators.mergeObjects(Operators.first(channelFilter), new Document("holders", Operators.concatArrays(List.of(holderData), Operators.filter(Operators.ifNull(Operators.first(Operators.map(channelFilter, "$$this.holders")), Collections.EMPTY_LIST), Operators.ne("$$this.id", holderId))))))));
			}

			concat.add(Operators.filter(channelMap, Operators.not(Operators.in("$$this.id", channelIds))));
			List<Bson> update = List.of(Operators.set("whitelist", Operators.concatArrays(concat)));
			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure((role ? ((Role) holder).getAsMention() : ((Member) holder).getUser().getAsMention()) + " is already whitelisted in all of the provided channels").queue();
					return;
				}

				event.replySuccess((role ? ((Role) holder).getAsMention() : ((Member) holder).getUser().getAsMention()) + " is now whitelisted in the provided channels").queue();
			});
		}

		@Command(value="remove", description="Removes a group whitelist from channels")
		@CommandId(119)
		@Examples({"anti regex whitelist remove 5f023782ef9eba03390a740c #youtube-links 2 youtube.com", "anti regex whitelist remove 5f023782ef9eba03390a740c 0 https://youtube.com"})
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		public void remove(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="channel", nullDefault=true) TextChannel channelArgument, @Argument(value="group") @Limit(min=0) int group, @Argument(value="string", endless=true) String string) {
			List<TextChannel> channels = channelArgument == null ? event.getGuild().getTextChannels() : List.of(channelArgument);

			Bson channelMap = Operators.ifNull("$whitelist", Collections.EMPTY_LIST);

			List<Bson> concat = new ArrayList<>();
			List<Long> channelIds = new ArrayList<>();
			for (TextChannel channel : channels) {
				long channelId = channel.getIdLong();
				channelIds.add(channelId);

				Bson channelFilter = Operators.filter(channelMap, Operators.eq("$$this.id", channelId));
				Bson groupMap = Operators.ifNull(Operators.first(Operators.map(channelFilter, "$$this.groups")), Collections.EMPTY_LIST);
				Bson groupFilter = Operators.filter(groupMap, Operators.eq("$$this.group", group));

				concat.add(Operators.cond(Operators.or(Operators.isEmpty(channelFilter), Operators.isEmpty(groupFilter)), Collections.EMPTY_LIST, List.of(Operators.mergeObjects(Operators.first(channelFilter), new Document("groups", Operators.concatArrays(List.of(Operators.mergeObjects(Operators.first(groupFilter), new Document("strings", Operators.filter(Operators.first(Operators.map(groupFilter, "$$this.strings")), Operators.ne("$$this", string))))), Operators.filter(groupMap, Operators.ne("$$this.group", group))))))));
			}

			concat.add(Operators.filter(channelMap, Operators.not(Operators.in("$$this.id", channelIds))));
			List<Bson> update = List.of(Operators.set("whitelist", Operators.concatArrays(concat)));

			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure("Group **" + group + "** is not whitelisted from that string in any of the provided channels").queue();
					return;
				}

				event.replySuccess("Group **" + group + "** is no longer whitelisted from that string in the provided channels").queue();
			});
		}

		@Command(value="remove", description="Removes a role or user whitelist from channels")
		@CommandId(120)
		@Examples({"anti regex whitelist remove 5f023782ef9eba03390a740c #channel @everyone", "anti regex whitelist remove 5f023782ef9eba03390a740c @Shea#6653"})
		@AuthorPermissions(permissions={Permission.MANAGE_SERVER})
		public void remove(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="channel", nullDefault=true) TextChannel channelArgument, @Argument(value="user | role") IPermissionHolder holder) {
			List<TextChannel> channels = channelArgument == null ? event.getGuild().getTextChannels() : List.of(channelArgument);

			Bson channelMap = Operators.ifNull("$whitelist", Collections.EMPTY_LIST);

			boolean role = holder instanceof Role;
			long holderId = holder.getIdLong();

			Document holderData = new Document("id", holderId).append("type", role ? HolderType.ROLE.getType() : HolderType.USER.getType());

			List<Bson> concat = new ArrayList<>();
			List<Long> channelIds = new ArrayList<>();
			for (TextChannel channel : channels) {
				long channelId = channel.getIdLong();
				channelIds.add(channelId);

				Document channelData = new Document("id", channelId).append("type", WhitelistType.CHANNEL.getId()).append("holders", List.of(holderData));

				Bson channelFilter = Operators.filter(channelMap, Operators.eq("$$this.id", channelId));
				concat.add(Operators.cond(Operators.isEmpty(channelFilter), List.of(channelData), List.of(Operators.mergeObjects(Operators.first(channelFilter), new Document("holders", Operators.filter(Operators.ifNull(Operators.first(Operators.map(channelFilter, "$$this.holders")), Collections.EMPTY_LIST), Operators.ne("$$this.id", holderId)))))));
			}

			concat.add(Operators.filter(channelMap, Operators.not(Operators.in("$$this.id", channelIds))));
			List<Bson> update = List.of(Operators.set("whitelist", Operators.concatArrays(concat)));

			event.getMongo().updateRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), update).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getMatchedCount() == 0) {
					event.replyFailure("I could not find that anti regex").queue();
					return;
				}

				if (result.getModifiedCount() == 0) {
					event.replyFailure((role ? ((Role) holder).getAsMention() : ((Member) holder).getUser().getAsMention()) + " is not whitelisted in any of the provided channels").queue();
					return;
				}

				event.replySuccess((role ? ((Role) holder).getAsMention() : ((Member) holder).getUser().getAsMention()) + " is no longer whitelisted in the provided channels").queue();
			});
		}

		@Command(value="list", description="Lists regex groups, roles and users that are whitelisted from specific channels for an anti regex")
		@CommandId(121)
		@Examples({"anti regex whitelist list 5f023782ef9eba03390a740c"})
		public void list(Sx4CommandEvent event, @Argument(value="id") ObjectId id, @Argument(value="channels", nullDefault=true) TextChannel channel) {
			List<TextChannel> channels = channel == null ? event.getGuild().getTextChannels() : List.of(channel);

			Document regex = event.getMongo().getRegex(Filters.and(Filters.eq("_id", id), Filters.eq("guildId", event.getGuild().getIdLong())), Projections.include("whitelist"));
			if (regex == null) {
				event.replyFailure("I could not find that anti regex").queue();
				return;
			}

			PagedResult<TextChannel> channelPaged = new PagedResult<>(event.getBot(), channels)
				.setAutoSelect(true)
				.setAuthor("Channels", null, event.getGuild().getIconUrl())
				.setDisplayFunction(TextChannel::getAsMention);

			channelPaged.onSelect(channelSelect -> {
				TextChannel selectedChannel = channelSelect.getSelected();

				Document whitelist = regex.getList("whitelist", Document.class).stream()
					.filter(w -> w.getLong("id") == selectedChannel.getIdLong())
					.findFirst()
					.orElse(null);

				if (whitelist == null) {
					event.replyFailure("Nothing is whitelisted for that anti regex in " + selectedChannel.getAsMention()).queue();
					return;
				}

				PagedResult<String> typePaged = new PagedResult<>(event.getBot(), List.of("Groups", "Users/Roles"))
					.setAuthor("Type", null, event.getGuild().getIconUrl())
					.setDisplayFunction(String::toString);

				typePaged.onSelect(typeSelect -> {
					String typeSelected = typeSelect.getSelected();

					boolean groups = typeSelected.equals("Groups");

					List<Document> whitelists = whitelist.getList(groups ? "groups" : "holders", Document.class, Collections.emptyList());
					if (whitelists.isEmpty()) {
						event.replyFailure("Nothing is whitelisted in " + typeSelected.toLowerCase() + " for that anti regex in " + selectedChannel.getAsMention()).queue();
						return;
					}

					PagedResult<Document> whitelistPaged = new PagedResult<>(event.getBot(), whitelists)
						.setAuthor(typeSelected, null, event.getGuild().getIconUrl())
						.setDisplayFunction(data -> {
							if (groups) {
								return "Group " + data.getInteger("group");
							} else {
								long holderId = data.getLong("id");
								int type = data.getInteger("type");
								if (type == HolderType.ROLE.getType()) {
									Role role = event.getGuild().getRoleById(holderId);
									return role == null ? "Deleted Role (" + holderId + ")" : role.getAsMention();
								} else {
									User user = event.getShardManager().getUserById(holderId);
									return user == null ? "Unknown User (" + holderId + ")" : user.getAsTag();
								}
							}
						});

					if (!groups) {
						whitelistPaged.setSelect().setIndexed(false);
					}

					whitelistPaged.onSelect(whitelistSelect -> {
						List<String> strings = whitelistSelect.getSelected().getList("strings", String.class, Collections.emptyList());
						if (strings.isEmpty()) {
							event.replyFailure("No strings are whitelisted in this group").queue();
							return;
						}

						PagedResult<String> stringPaged = new PagedResult<>(event.getBot(), strings)
							.setAuthor("Strings", null, event.getGuild().getIconUrl())
							.setDisplayFunction(MarkdownSanitizer::sanitize)
							.setSelect()
							.setIndexed(false);

						stringPaged.execute(event);
					});

					whitelistPaged.execute(event);
				});

				typePaged.execute(event);
			});

			channelPaged.execute(event);
		}
	}

	public static class TemplateCommand extends Sx4Command {

		public TemplateCommand() {
			super("template", 122);

			super.setDescription("Create regex templates for anti regex");
			super.setExamples("anti regex template add", "anti regex template list");
			super.setCategoryAll(ModuleCategory.AUTO_MODERATION);
		}

		public void onCommand(Sx4CommandEvent event) {
			event.replyHelp().queue();
		}

		@Command(value="add", description="Add a regex to the templates for anyone to use")
		@CommandId(123)
		@Examples({"anti regex template add Numbers .*[0-9]+.* Will match any message which contains a number"})
		public void add(Sx4CommandEvent event, @Argument(value="title") @Limit(max=20) String title, @Argument(value="regex") Pattern pattern, @Argument(value="description", endless=true) @Limit(max=250) String description) {
			String patternString = pattern.pattern();
			if (patternString.length() > 200) {
				event.replyFailure("The regex cannot be more than 200 characters").queue();
				return;
			}

			Document data = new Document("title", title)
				.append("description", description)
				.append("pattern", patternString)
				.append("type", RegexType.REGEX.getId())
				.append("ownerId", event.getAuthor().getIdLong());

			event.getMongo().insertRegexTemplate(data).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				event.replySuccess("Your regex template has been added").queue();
			});
		}

		@Command(value="remove", description="Remove your own template from the anti regex templates")
		@CommandId(448)
		@Examples({"anti regex template remove 5f023782ef9eba03390a740c"})
		public void remove(Sx4CommandEvent event, @Argument(value="id") ObjectId id) {
			Bson filter = Filters.eq("_id", id);
			if (!event.isAuthorDeveloper()) {
				filter = Filters.and(filter, Filters.eq("ownerId", event.getAuthor().getIdLong()));
			}

			event.getMongo().deleteRegexTemplate(filter).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				if (result.getDeletedCount() == 0) {
					event.replyFailure("I could not find that regex template owned by you").queue();
					return;
				}

				event.replySuccess("That regex template has been removed").queue();
			});
		}

		@Command(value="list", description="Lists the regexes which you can use for anti regex")
		@CommandId(124)
		@Examples({"anti regex template list"})
		public void list(Sx4CommandEvent event) {
			List<Document> list = event.getMongo().getRegexTemplates(Filters.empty(), Projections.include("title", "description", "pattern", "ownerId", "uses")).sort(Sorts.descending("uses")).into(new ArrayList<>());
			if (list.isEmpty()) {
				event.replyFailure("There are no regex templates currently").queue();
				return;
			}

			PagedResult<Document> paged = new PagedResult<>(event.getBot(), list)
				.setPerPage(6)
				.setCustomFunction(page -> {
					MessageBuilder builder = new MessageBuilder();

					EmbedBuilder embed = new EmbedBuilder();
					embed.setAuthor("Regex Template List", null, event.getSelfUser().getEffectiveAvatarUrl());
					embed.setTitle("Page " + page.getPage() + "/" + page.getMaxPage());
					embed.setFooter(PagedResult.DEFAULT_FOOTER_TEXT, null);

					page.forEach((data, index) -> {
						User owner = event.getShardManager().getUserById(data.getLong("ownerId"));

						embed.addField(data.getString("title"), String.format("Id: %s\nRegex: `%s`\nUses: %,d\nOwner: %s\nDescription: %s", data.getObjectId("_id").toHexString(), data.getString("pattern"), data.getLong("uses"), owner == null ? "Annonymous#0000" : owner.getAsTag(), data.getString("description")), true);
					});

					return builder.setEmbeds(embed.build());
				});

			paged.execute(event);
		}

	}
	
}
