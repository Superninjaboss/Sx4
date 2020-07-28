package com.sx4.bot.commands.management;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.command.Command;
import com.jockie.bot.core.command.Command.Cooldown;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.*;
import com.sx4.bot.annotations.argument.ExcludeUpdate;
import com.sx4.bot.annotations.argument.Limit;
import com.sx4.bot.annotations.command.AuthorPermissions;
import com.sx4.bot.annotations.command.BotPermissions;
import com.sx4.bot.annotations.command.Examples;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.database.Database;
import com.sx4.bot.database.model.Operators;
import com.sx4.bot.entities.argument.All;
import com.sx4.bot.entities.argument.MessageArgument;
import com.sx4.bot.entities.argument.UpdateType;
import com.sx4.bot.entities.settings.HolderType;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.waiter.Waiter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.IPermissionHolder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageReaction.ReactionEmote;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

public class ReactionRoleCommand extends Sx4Command {

	private final Predicate<Throwable> defaultReactionFailure = exception -> {
		if (exception instanceof ErrorResponseException) {
			ErrorResponseException errorResponse = ((ErrorResponseException) exception);
			return errorResponse.getErrorCode() == 400 || errorResponse.getErrorResponse() == ErrorResponse.UNKNOWN_EMOJI;
		}
		
		return false;
	};

	private static Predicate<Document> getReactionFilter(ReactionEmote emote) {
		return data -> {
			Document emoteData = data.get("emote", Document.class);
			if (emote.isEmoji()) {
				if (emoteData.containsKey("name")) {
					return emoteData.getString("name").equals(emote.getEmoji());
				}

				return false;
			} else {
				return emoteData.get("id", 0L) == emote.getEmote().getIdLong();
			}
		};
	}
	
	public ReactionRoleCommand() {
		super("reaction role");
		
		super.setDescription("Set up a reaction role so users can simply react to an emote and get a specified role");
		super.setAliases("reactionrole");
		super.setExamples("reaction role add", "reaction role remove");
	}
	
	public void onCommand(Sx4CommandEvent event) {
		event.replyHelp().queue();
	}
	
	@Command(value="add", description="Adds a role to be given when a user reacts to the specified emote")
	@Examples({"reaction role add 643945552865919002 🐝 @Yellow", "reaction role add https://discordapp.com/channels/330399610273136641/678274453158887446/680051429460803622 :doggo: Dog person"})
	@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
	@BotPermissions(permissions={Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_HISTORY})
	@Cooldown(value=2)
	public void add(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote") ReactionEmote emote, @Argument(value="role", endless=true) Role role) {
		if (role.isPublicRole()) {
			event.reply("I cannot give the `@everyone` role " + this.config.getFailureEmote()).queue();
			return;
		}
		
		if (role.isManaged()) {
			event.reply("I cannot give managed roles " + this.config.getFailureEmote()).queue();
			return;
		}
		
		if (!event.getSelfMember().canInteract(role)) {
			event.reply("I cannot give a role higher or equal than my top role " + this.config.getFailureEmote()).queue();
			return;
		}
		
		if (!event.getMember().canInteract(role)) {
			event.reply("You cannot give a role higher or equal than your top role " + this.config.getFailureEmote()).queue();
			return;
		}
		
		boolean unicode = emote.isEmoji();
		String identifier = unicode ? "name" : "id";
		messageArgument.getRestAction().queue(message -> {
			if (message.getReactions().size() >= 20) {
				event.reply("That message is at the max amount of reactions (20) " + this.config.getFailureEmote()).queue();
				return;
			}

			Document reactionData = new Document("emote", new Document(identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()))
				.append("roles", List.of(role.getIdLong()));

			Object emoteStore = unicode ? emote.getEmoji() : emote.getEmote().getIdLong();

			Bson reactionRoles = Operators.ifNull("$reactionRole.reactionRoles", Collections.EMPTY_LIST);
			Bson messageFilter = Operators.filter(reactionRoles, Operators.eq("$$this.id", message.getIdLong()));
			Bson reactionMap = Operators.ifNull(Operators.first(Operators.map(messageFilter, "$$this.reactions")), Collections.EMPTY_LIST);
			Bson reactionFilter = Operators.filter(reactionMap, Operators.eq("$$this.emote." + identifier, emoteStore));
			Bson roleMap = Operators.ifNull(Operators.first(Operators.map(reactionFilter, "$$this.roles")), Collections.EMPTY_LIST);

			Bson concat = Operators.cond(Operators.isEmpty(messageFilter), List.of(new Document("id", message.getIdLong()).append("reactions", List.of(reactionData))), Operators.cond(Operators.isEmpty(reactionFilter), List.of(Operators.mergeObjects(Operators.first(messageFilter), new Document("reactions", Operators.concatArrays(List.of(reactionData), Operators.filter(reactionMap, Operators.ne("$$this.emote." + identifier, emoteStore)))))), List.of(Operators.mergeObjects(Operators.first(messageFilter), new Document("reactions", Operators.concatArrays(List.of(Operators.mergeObjects(Operators.first(reactionFilter), new Document("roles", Operators.concatArrays(Operators.filter(roleMap, Operators.ne("$$this", role.getIdLong())), List.of(role.getIdLong()))))), Operators.filter(reactionMap, Operators.ne("$$this.emote." + identifier, emoteStore))))))));
			List<Bson> update = List.of(Operators.set("reactionRole.reactionRoles", Operators.concatArrays(concat, Operators.filter(reactionRoles, Operators.ne("$$this.id", message.getIdLong())))));

			if (unicode && message.getReactionByUnicode(emote.getEmoji()) == null) {
				message.addReaction(emote.getEmoji()).queue($ -> {
					this.database.updateGuildById(event.getGuild().getIdLong(), update).whenComplete((result, exception) -> {
						if (ExceptionUtility.sendExceptionally(event, exception)) {
							return;
						}

						if (result.getModifiedCount() == 0) {
							event.reply("That role is already given when reacting to this reaction " + this.config.getFailureEmote()).queue();
							return;
						}
						
						event.reply("The role " + role.getAsMention() + " will now be given when reacting to " + emote.getEmoji() + " " + this.config.getSuccessEmote()).queue();
					});
				}, new ErrorHandler().handle(this.defaultReactionFailure, exception -> event.reply("I could not find that emote " + this.config.getFailureEmote()).queue()));
			} else {
				if (!unicode && message.getReactionById(emote.getEmote().getIdLong()) == null) {
					message.addReaction(emote.getEmote()).queue();
				}
				
				this.database.updateGuildById(event.getGuild().getIdLong(), update).whenComplete((result, exception) -> {
					if (ExceptionUtility.sendExceptionally(event, exception)) {
						return;
					}

					if (result.getModifiedCount() == 0) {
						event.reply("That role is already given when reacting to this reaction " + this.config.getFailureEmote()).queue();
						return;
					}
					
					event.reply("The role " + role.getAsMention() + " will now be given when reacting to " + (unicode ? emote.getEmoji() : emote.getEmote().getAsMention()) + " " + this.config.getSuccessEmote()).queue();
				});
			}
		}, new ErrorHandler().handle(ErrorResponse.UNKNOWN_MESSAGE, exception -> event.reply("I could not find that message " + this.config.getFailureEmote()).queue()));
	}
	
	@Command(value="remove", description="Removes a role or a whole reaction from the reaction role")
	@Examples({"reaction role remove 643945552865919002 🐝", "reaction role remove https://discordapp.com/channels/330399610273136641/678274453158887446/680051429460803622 🐝 @Yellow"})
	@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
	@Cooldown(value=2)
	public void remove(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote") ReactionEmote emote, @Argument(value="role", endless=true, nullDefault=true) Role role) {
		boolean unicode = emote.isEmoji();
		String identifier = unicode ? "name" : "id";
		
		Bson update;
		List<Bson> arrayFilters;
		if (role == null) {
			update = Updates.pull("reactionRole.reactionRoles.$[reactionRole].reactions", Filters.eq("emote." + identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()));
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageArgument.getMessageId()));
		} else {
			update = Updates.pull("reactionRole.reactionRoles.$[reactionRole].reactions.$[reaction].roles", role.getIdLong());
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageArgument.getMessageId()), Filters.eq("reaction.emote." + identifier, unicode ? emote.getEmoji() : emote.getEmote().getIdLong()));
		}
		
		FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().arrayFilters(arrayFilters).returnDocument(ReturnDocument.BEFORE).projection(Projections.include("reactionRole.reactionRoles"));
		this.database.findAndUpdateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((data, exception) -> {
			if (exception instanceof CompletionException) {
				Throwable cause = exception.getCause();
				if (cause instanceof MongoWriteException && ((MongoWriteException) cause).getCode() == 2) {
					event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}
			}
				
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}
			
			if (data == null) {
				event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
				return;
			}
			
			List<Document> reactionRoles = data.getEmbedded(List.of("reactionRole", "reactionRoles"), Collections.emptyList());

			Document reactionRole = reactionRoles.stream()
				.filter(d -> d.getLong("id") == messageArgument.getMessageId())
				.findFirst()
				.orElse(null);
			
			if (reactionRole == null) {
				event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
				return;
			}
			
			Document reaction = reactionRole.getList("reactions", Document.class).stream()
				.filter(ReactionRoleCommand.getReactionFilter(emote))
				.findFirst()
				.orElse(null);
			
			if (reaction == null) {
				event.reply("There was no reaction role for that emote " + this.config.getFailureEmote()).queue();
				return;
			}
			
			if (role == null) {
				event.reply("The reaction " + (unicode ? emote.getEmoji() : emote.getEmote().getAsMention()) + " will no longer give any roles " + this.config.getSuccessEmote()).queue();
			} else {
				if (!reaction.getList("roles", Long.class).contains(role.getIdLong())) {
					event.reply("That role is not given when reacting to that emote " + this.config.getFailureEmote()).queue();
					return;
				}
			
				event.reply("The role " + role.getAsMention() + " has been removed from that reaction " + this.config.getSuccessEmote()).queue();
			}
		});
	}
	
	@Command(value="dm", description="Enables/disables whether a reaction role should send dms when a user acquires roles")
	@Examples({"reaction role dm enable all", "reaction role dm disable all", "reaction role dm enable 643945552865919002", "reaction role dm disable 643945552865919002"})
	@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
	@Cooldown(value=2)
	public void dm(Sx4CommandEvent event, @Argument(value="update type") @ExcludeUpdate(UpdateType.TOGGLE) UpdateType updateType, @Argument(value="message id") All<MessageArgument> allArgument) {
		boolean all = allArgument.isAll(), value = updateType.getValue();
		long messageId = all ? 0L : allArgument.getValue().getMessageId();
		
		Bson update;
		List<Bson> arrayFilters;
		if (all) {
			update = value ? Updates.unset("reactionRole.reactionRoles.$[].dm") : Updates.set("reactionRole.reactionRoles.$[].dm", false);
			arrayFilters = null;
		} else {
			update = value ? Updates.unset("reactionRole.reactionRoles.$[reactionRole].dm") : Updates.set("reactionRole.reactionRoles.$[reactionRole].dm", false);
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageId));
		}
		
		UpdateOptions options = new UpdateOptions().arrayFilters(arrayFilters);
		this.database.updateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((result, exception) -> {
			if (exception instanceof CompletionException) {
				Throwable cause = exception.getCause();
				if (cause instanceof MongoWriteException && ((MongoWriteException) cause).getCode() == 2) {
					event.reply(all ? "You do not have any reaction roles setup " + this.config.getFailureEmote() : "There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}
			}
				
			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}
			
			if (result.getModifiedCount() == 0 && result.getMatchedCount() != 0) {
				event.reply((all ? "All your reaction roles" : "That reaction role") + " already " + (all ? "have" : "has") + " it set to " + (value ? "" : "not ") + "dm users " + this.config.getFailureEmote()).queue();
				return;
			}
			
			if (result.getModifiedCount() == 0) {
				event.reply(all ? "You do not have any reaction roles setup " + this.config.getFailureEmote() : "There was no reaction role on that message " + this.config.getFailureEmote()).queue();
				return;
			}
			
			event.reply((all ? "All your reaction roles" : "That reaction role") + " will " + (value ? "now" : "no longer") + " send dms " + this.config.getSuccessEmote()).queue();
		});
	}
	
	@Command(value="max reactions", aliases={"maxreactions"}, description="Sets the max amount of reactions a user can react to simultanously")
	@Examples({"reaction role max reactions all 2", "reaction role max reactions 643945552865919002 0"})
	@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
	@Cooldown(value=2)
	public void maxReactions(Sx4CommandEvent event, @Argument(value="message id") All<MessageArgument> allArgument, @Argument(value="max reactions") @Limit(min=0, max=20) int maxReactions) {
		boolean all = allArgument.isAll(), unlimited = maxReactions == 0;
		long messageId = all ? 0L : allArgument.getValue().getMessageId();
		
		Bson update;
		List<Bson> arrayFilters;
		if (all) {
			update = unlimited ? Updates.unset("reactionRole.reactionRoles.$[].maxReactions") : Updates.set("reactionRole.reactionRoles.$[].maxReactions", maxReactions);
			arrayFilters = null;
		} else {
			update = unlimited ? Updates.unset("reactionRole.reactionRoles.$[reactionRole].maxReactions") : Updates.set("reactionRole.reactionRoles.$[reactionRole].maxReactions", maxReactions);
			arrayFilters = List.of(Filters.eq("reactionRole.id", messageId));
		}
		
		UpdateOptions options = new UpdateOptions().arrayFilters(arrayFilters);
		this.database.updateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((result, exception) -> {
			if (exception instanceof CompletionException) {
				Throwable cause = exception.getCause();
				if (cause instanceof MongoWriteException && ((MongoWriteException) cause).getCode() == 2) {
					event.reply(all ? "You do not have any reaction roles setup " + this.config.getFailureEmote() : "There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}
			}

			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}
			
			if (result.getModifiedCount() == 0 && result.getMatchedCount() != 0) {
				event.reply((all ? "All your reaction roles" : "That reaction role") + " already " + (all ? "have" : "has") + " it set to " + (unlimited ? "unlimted" : "**" + maxReactions + "**") + " max reaction" + (maxReactions == 1 ? "" : "s") + "" + this.config.getFailureEmote()).queue();
				return;
			}
			
			if (result.getModifiedCount() == 0) {
				event.reply(all ? "You do not have any reaction roles setup " + this.config.getFailureEmote() : "There was no reaction role on that message " + this.config.getFailureEmote()).queue();
				return;
			}
			
			event.reply((all ? "All your reaction roles" : "That reaction role") + " now " + (all ? "have " : "has ") + (unlimited ? "no cap for" : "a cap of **" + maxReactions + "**") + " reaction" + (maxReactions == 1 ? "" : "s") + " " + this.config.getSuccessEmote()).queue();
		});
	}
	
	@Command(value="delete", description="Deletes a reaction role")
	@Examples({"reaction role delete 643945552865919002", "reaction role delete all"})
	@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
	@Cooldown(value=5)
	public void delete(Sx4CommandEvent event, @Argument(value="message id") All<MessageArgument> allArgument) {
		if (allArgument.isAll()) {
			event.reply(event.getAuthor().getName() + ", are you sure you want to delete **all** the reaction roles in this server? (Yes or No)").submit()
				.thenCompose(message -> {
					Waiter<GuildMessageReceivedEvent> waiter = new Waiter<>(GuildMessageReceivedEvent.class)
						.setPredicate(messageEvent -> messageEvent.getMessage().getContentRaw().equalsIgnoreCase("yes"))
						.setOppositeCancelPredicate()
						.setTimeout(30)
						.setUnique(event.getAuthor().getIdLong(), event.getChannel().getIdLong());
					
					waiter.onTimeout(() -> event.reply("Response timed out :stopwatch:").queue());
					
					waiter.onCancelled(type -> event.reply("Cancelled " + this.config.getSuccessEmote()).queue());
					
					waiter.start();
					
					return waiter.future();
				})
				.thenCompose(messageEvent -> this.database.updateGuildById(event.getGuild().getIdLong(), Updates.unset("reactionRole.reactionRoles")))
				.whenComplete((result, exception) -> {
					if (ExceptionUtility.sendExceptionally(event, exception)) {
						return;
					}
					
					event.reply("All reaction role data has been deleted in this server " + this.config.getSuccessEmote()).queue();
				});
		} else {
			long messageId = allArgument.getValue().getMessageId();
			this.database.updateGuildById(event.getGuild().getIdLong(), Updates.pull("reactionRole.reactionRoles", Filters.eq("id", messageId))).whenComplete((result, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}
				
				if (result.getModifiedCount() == 0) {
					event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}
				
				event.reply("That reaction role has been deleted " + this.config.getSuccessEmote()).queue();
			});
		}
	}

	public class WhitelistCommand extends Sx4Command {

		public WhitelistCommand() {
			super("whitelist");

			super.setDescription("Whitelists a user or role from being able to use the reaction on the reaction role");
			super.setExamples("reaction role whitelist add", "reaction role whitelist remove", "reaction role whitelist list");
		}

		public void onCommand(Sx4CommandEvent event) {
			event.replyHelp().queue();
		}

		@Command(value="add", description="Adds a whitelist for a user or role to be able to use a reaction on a reaction role")
		@Examples({"reaction role whitelist add 643945552865919002 🐝 @Shea#6653", "reaction role whitelist add 643945552865919002 :doggo: @Role", "reaction role whitelist add 643945552865919002 @Role"})
		@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
		public void add(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote", nullDefault=true) ReactionEmote emote, @Argument(value="user | role", endless=true)IPermissionHolder holder) {
			long messageId = messageArgument.getMessageId();
			boolean role = holder instanceof Role;

			Document holderData = new Document("id", holder.getIdLong())
				.append("type", role ? HolderType.ROLE.getType() : HolderType.USER.getType());

			Bson reactionRoleFilter = Operators.filter("$reactionRole.reactionRoles", Operators.eq("$$this.id", messageId));
			Bson reactionsFilter = Operators.first(Operators.map(reactionRoleFilter, "$$this.reactions"));

			List<Bson> update;
			if (emote != null) {
				boolean emoji = emote.isEmoji();

				Bson reaction = Operators.filter(reactionsFilter, Operators.eq("$$this.emote." + (emoji ? "name" : "id"), emoji ? emote.getEmoji() : emote.getEmote().getIdLong()));
				Bson whitelist = Operators.ifNull(Operators.first(Operators.map(reaction, "$$this.whitelist")), Collections.EMPTY_LIST);
				Bson holderFilter = Operators.filter(whitelist, Operators.eq("$$this.id", holder.getIdLong()));

				Bson result = Operators.concatArrays(Operators.filter("$reactionRole.reactionRoles", Operators.ne("$$this.id", messageId)), List.of(Operators.mergeObjects(Operators.first(reactionRoleFilter), new Document("reactions", Operators.concatArrays(List.of(Operators.mergeObjects(Operators.first(reaction), new Document("whitelist", Operators.concatArrays(whitelist, Operators.cond(Operators.isEmpty(holderFilter), List.of(holderData), Collections.EMPTY_LIST))))), Operators.filter(reactionsFilter, Operators.ne("$$this.emote." + (emoji ? "name" : "id"), emoji ? emote.getEmoji() : emote.getEmote().getIdLong())))))));
				update = List.of(Operators.set("reactionRole.reactionRoles", Operators.cond(Operators.or(Operators.isEmpty(reactionRoleFilter), Operators.isEmpty(reaction)), "$reactionRole.reactionRoles", result)));
			} else {
				Bson result = Operators.concatArrays(Operators.filter("$reactionRole.reactionRoles", Operators.ne("$$this.id", messageId)), List.of(Operators.mergeObjects(Operators.first(reactionRoleFilter), new Document("reactions", Operators.map(reactionsFilter, Operators.mergeObjects("$$this", new Document("whitelist", Operators.concatArrays(Operators.ifNull("$$this.whitelist", Collections.EMPTY_LIST), Operators.cond(Operators.isEmpty(Operators.filter(Operators.ifNull("$$this.whitelist", Collections.EMPTY_LIST), Operators.eq("$$this.id", holder.getIdLong()))), List.of(holderData), Collections.EMPTY_LIST)))))))));
				update = List.of(Operators.set("reactionRole.reactionRoles", Operators.cond(Operators.isEmpty(reactionRoleFilter), "$reactionRole.reactionRoles", result)));
			}

			FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().projection(Projections.include("reactionRole.reactionRoles")).returnDocument(ReturnDocument.BEFORE);
			this.database.findAndUpdateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((data, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				data = data == null ? Database.EMPTY_DOCUMENT : data;

				List<Document> reactionRoles = data.getEmbedded(List.of("reactionRole", "reactionRoles"), Collections.emptyList());
				Document reactionRole = reactionRoles.stream()
					.filter(d -> d.get("id", 0L) == messageId)
					.findFirst()
					.orElse(null);

				if (reactionRole == null) {
					event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}

				if (emote != null) {
					List<Document> reactions = reactionRole.getList("reactions", Document.class, Collections.emptyList());
					Document reaction = reactions.stream()
						.filter(ReactionRoleCommand.getReactionFilter(emote))
						.findFirst()
						.orElse(null);

					if (reaction == null) {
						event.reply("You do not have that reaction on that reaction role " + this.config.getFailureEmote()).queue();
						return;
					}
				}

				event.reply((role ? ((Role) holder).getAsMention() : ((Member) holder).getAsMention()) + " is now whitelisted from " + (emote == null ? "all reactions" : "the " + (emote.isEmoji() ? emote.getEmoji() : emote.getEmote().getAsMention()) + " reaction") + " on that reaction role " + this.config.getSuccessEmote()).queue();
			});
		}

		@Command(value="remove", description="Removes a whitelist from a user or role")
		@Examples({"reaction role whitelist remove 643945552865919002 🐝 @Shea#6653", "reaction role whitelist remove 643945552865919002 :doggo: @Role", "reaction role whitelist remove 643945552865919002 @Role"})
		@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
		public void remove(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote", nullDefault=true) ReactionEmote emote, @Argument(value="user | role", endless=true)IPermissionHolder holder) {
			long messageId = messageArgument.getMessageId();
			boolean role = holder instanceof Role;

			Document holderData = new Document("id", holder.getIdLong())
				.append("type", role ? HolderType.ROLE.getType() : HolderType.USER.getType());

			Bson reactionRoleFilter = Operators.filter("$reactionRole.reactionRoles", Operators.eq("$$this.id", messageId));
			Bson reactionsFilter = Operators.first(Operators.map(reactionRoleFilter, "$$this.reactions"));

			List<Bson> update;
			if (emote != null) {
				boolean emoji = emote.isEmoji();

				Bson reaction = Operators.filter(reactionsFilter, Operators.eq("$$this.emote." + (emoji ? "name" : "id"), emoji ? emote.getEmoji() : emote.getEmote().getIdLong()));
				Bson whitelist = Operators.ifNull(Operators.first(Operators.map(reaction, "$$this.whitelist")), Collections.EMPTY_LIST);

				Bson result = Operators.concatArrays(Operators.filter("$reactionRole.reactionRoles", Operators.ne("$$this.id", messageId)), List.of(Operators.mergeObjects(Operators.first(reactionRoleFilter), new Document("reactions", Operators.concatArrays(List.of(Operators.mergeObjects(Operators.first(reaction), new Document("whitelist", Operators.filter(whitelist, Operators.ne("$$this.id", holder.getIdLong()))))), Operators.filter(reactionsFilter, Operators.ne("$$this.emote." + (emoji ? "name" : "id"), emoji ? emote.getEmoji() : emote.getEmote().getIdLong())))))));
				update = List.of(Operators.set("reactionRole.reactionRoles", Operators.cond(Operators.or(Operators.isEmpty(reactionRoleFilter), Operators.isEmpty(reaction)), "$reactionRole.reactionRoles", result)));
			} else {
				Bson result = Operators.concatArrays(Operators.filter("$reactionRole.reactionRoles", Operators.ne("$$this.id", messageId)), List.of(Operators.mergeObjects(Operators.first(reactionRoleFilter), new Document("reactions", Operators.map(reactionsFilter, Operators.mergeObjects("$$this", new Document("whitelist", Operators.filter("$$this.whitelist", Operators.ne("$$this.id", holder.getIdLong())))))))));
				update = List.of(Operators.set("reactionRole.reactionRoles", Operators.cond(Operators.isEmpty(reactionRoleFilter), "$reactionRole.reactionRoles", result)));
			}

			FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().projection(Projections.include("reactionRole.reactionRoles")).returnDocument(ReturnDocument.BEFORE);
			this.database.findAndUpdateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((data, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				data = data == null ? Database.EMPTY_DOCUMENT : data;

				List<Document> reactionRoles = data.getEmbedded(List.of("reactionRole", "reactionRoles"), Collections.emptyList());
				Document reactionRole = reactionRoles.stream()
					.filter(d -> d.get("id", 0L) == messageId)
					.findFirst()
					.orElse(null);

				if (reactionRole == null) {
					event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}

				if (emote != null) {
					List<Document> reactions = reactionRole.getList("reactions", Document.class, Collections.emptyList());
					Document reaction = reactions.stream()
						.filter(ReactionRoleCommand.getReactionFilter(emote))
						.findFirst()
						.orElse(null);

					if (reaction == null) {
						event.reply("You do not have that reaction on that reaction role " + this.config.getFailureEmote()).queue();
						return;
					}

					List<Document> whitelists = reaction.getList("whitelist", Document.class, Collections.emptyList());
					if (whitelists.stream().noneMatch(d -> d.get("id", 0L) == holder.getIdLong())) {
						event.reply("That " + (role ? "role" : "user") + " is not whitelisted from that reaction " + this.config.getFailureEmote()).queue();
						return;
					}
				}

				event.reply((role ? ((Role) holder).getAsMention() : ((Member) holder).getAsMention()) + " is no longer whitelisted from " + (emote == null ? "any reactions" : "the " + (emote.isEmoji() ? emote.getEmoji() : emote.getEmote().getAsMention()) + " reaction") + " on that reaction role " + this.config.getSuccessEmote()).queue();
			});
		}

		@Command(value="delete", description="Deletes a whitelist for a reaction or all reactions")
		@Examples({"reaction role whitelist delete 643945552865919002 :doggo:", "reaction role whitelist delete 643945552865919002"})
		@AuthorPermissions(permissions={Permission.MANAGE_ROLES})
		public void delete(Sx4CommandEvent event, @Argument(value="message id") MessageArgument messageArgument, @Argument(value="emote", nullDefault=true) ReactionEmote emote) {
			long messageId = messageArgument.getMessageId();

			Bson reactionRoleFilter = Operators.filter("$reactionRole.reactionRoles", Operators.eq("$$this.id", messageId));
			Bson reactionsFilter = Operators.first(Operators.map(reactionRoleFilter, "$$this.reactions"));

			List<Bson> update;
			if (emote != null) {
				boolean emoji = emote.isEmoji();

				Bson reaction = Operators.filter(reactionsFilter, Operators.eq("$$this.emote." + (emoji ? "name" : "id"), emoji ? emote.getEmoji() : emote.getEmote().getIdLong()));

				Bson result = Operators.concatArrays(Operators.filter("$reactionRole.reactionRoles", Operators.ne("$$this.id", messageId)), List.of(Operators.mergeObjects(Operators.first(reactionRoleFilter), new Document("reactions", Operators.concatArrays(List.of(Operators.mergeObjects(Operators.first(reaction), new Document("whitelist", Collections.EMPTY_LIST))), Operators.filter(reactionsFilter, Operators.ne("$$this.emote." + (emoji ? "name" : "id"), emoji ? emote.getEmoji() : emote.getEmote().getIdLong())))))));
				update = List.of(Operators.set("reactionRole.reactionRoles", Operators.cond(Operators.or(Operators.isEmpty(reactionRoleFilter), Operators.isEmpty(reaction)), "$reactionRole.reactionRoles", result)));
			} else {
				Bson result = Operators.concatArrays(Operators.filter("$reactionRole.reactionRoles", Operators.ne("$$this.id", messageId)), List.of(Operators.mergeObjects(Operators.first(reactionRoleFilter), new Document("reactions", Operators.map(reactionsFilter, Operators.mergeObjects("$$this", new Document("whitelist", Collections.EMPTY_LIST)))))));
				update = List.of(Operators.set("reactionRole.reactionRoles", Operators.cond(Operators.isEmpty(reactionRoleFilter), "$reactionRole.reactionRoles", result)));
			}

			FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().projection(Projections.include("reactionRole.reactionRoles")).returnDocument(ReturnDocument.BEFORE);
			this.database.findAndUpdateGuildById(event.getGuild().getIdLong(), update, options).whenComplete((data, exception) -> {
				if (ExceptionUtility.sendExceptionally(event, exception)) {
					return;
				}

				data = data == null ? Database.EMPTY_DOCUMENT : data;

				List<Document> reactionRoles = data.getEmbedded(List.of("reactionRole", "reactionRoles"), Collections.emptyList());
				Document reactionRole = reactionRoles.stream()
					.filter(d -> d.get("id", 0L) == messageId)
					.findFirst()
					.orElse(null);

				if (reactionRole == null) {
					event.reply("There was no reaction role on that message " + this.config.getFailureEmote()).queue();
					return;
				}

				if (emote != null) {
					List<Document> reactions = reactionRole.getList("reactions", Document.class, Collections.emptyList());
					Document reaction = reactions.stream()
						.filter(ReactionRoleCommand.getReactionFilter(emote))
						.findFirst()
						.orElse(null);

					if (reaction == null) {
						event.reply("You do not have that reaction on that reaction role " + this.config.getFailureEmote()).queue();
						return;
					}

					List<Document> whitelists = reaction.getList("whitelist", Document.class, Collections.emptyList());
					if (whitelists.isEmpty()) {
						event.reply("That reaction does not have any whitelists " + this.config.getFailureEmote()).queue();
						return;
					}
				}

				event.reply("There are no longer any whitelists on " + (emote == null ? "any reactions" : "the " + (emote.isEmoji() ? emote.getEmoji() : emote.getEmote().getAsMention()) + " reaction") + " on that reaction role " + this.config.getSuccessEmote()).queue();
			});
		}

	}
	
}