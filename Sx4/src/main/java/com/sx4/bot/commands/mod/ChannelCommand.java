package com.sx4.bot.commands.mod;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.command.Command;
import com.sx4.bot.annotations.argument.EnumOptions;
import com.sx4.bot.annotations.command.*;
import com.sx4.bot.category.ModuleCategory;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.utility.LoggerUtility;
import com.sx4.bot.utility.SearchUtility;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;

public class ChannelCommand extends Sx4Command {

	public ChannelCommand() {
		super("channel", 243);

		super.setDescription("Allows you to do multiple actions with channels");
		super.setExamples("channel create", "channel delete");
		super.setCategoryAll(ModuleCategory.MODERATION);
	}

	public void onCommand(Sx4CommandEvent event) {
		event.replyHelp().queue();
	}

	@Command(value="create", description="Create a channel within the current server")
	@CommandId(244)
	@Redirects({"channel create", "cc"})
	@Examples({"channel create bots", "channel create voice Music", "channel create category Info"})
	@AuthorPermissions(permissions={Permission.MANAGE_CHANNEL})
	@BotPermissions(permissions={Permission.MANAGE_CHANNEL})
	public void create(Sx4CommandEvent event, @Argument(value="type", nullDefault=true) @EnumOptions(value={"TEXT", "VOICE", "CATEGORY"}) ChannelType type, @Argument(value="name", endless=true) String name) {
		type = type == null ? ChannelType.TEXT : type;

		if (type == ChannelType.TEXT) {
			event.getGuild().createTextChannel(name)
				.flatMap(channel -> event.replySuccess("The text channel " + channel.getAsMention() + " has been created"))
				.queue();
		} else if (type == ChannelType.CATEGORY) {
			event.getGuild().createCategory(name)
				.flatMap(channel -> event.replySuccess("The category **" + channel.getName() + "** has been created"))
				.queue();
		} else if (type == ChannelType.VOICE) {
			event.getGuild().createVoiceChannel(name)
				.flatMap(channel -> event.replySuccess("The voice channel **" + channel.getAsMention() + "** has been created"))
				.queue();
		}
	}

	@Command(value="delete", description="Deletes a channel within the current server")
	@CommandId(245)
	@Redirects({"channel delete", "dc"})
	@Examples({"channel delete #bots", "channel create voice Music", "channel create category Info"})
	@AuthorPermissions(permissions={Permission.MANAGE_CHANNEL})
	@BotPermissions(permissions={Permission.MANAGE_CHANNEL})
	public void delete(Sx4CommandEvent event, @Argument(value="type", nullDefault=true) @EnumOptions(value={"TEXT", "VOICE", "CATEGORY", "STORE"}) ChannelType type, @Argument(value="channel", endless=true) String query) {
		ChannelType effectiveType = type == null ? ChannelType.TEXT : type;

		GuildChannel channel = SearchUtility.getGuildChannel(event.getGuild(), effectiveType, query);
		if (channel == null) {
			event.replyFailure("I could not find that " + LoggerUtility.getChannelTypeReadable(type)).queue();
			return;
		}

		channel.delete()
			.flatMap($ -> event.replySuccess("The " + LoggerUtility.getChannelTypeReadable(effectiveType) + " **" + channel.getName() + "** has been deleted"))
			.queue();
	}

	@Command(value="mute", description="Mute a user or role in a channel")
	@CommandId(335)
	@Examples({"channel mute @Shea#6653", "channel mute @Role", "channel mute #channel @Shea#6653"})
	@AuthorPermissions(permissions={Permission.MANAGE_PERMISSIONS})
	@BotPermissions(permissions={Permission.MANAGE_PERMISSIONS})
	public void mute(Sx4CommandEvent event, @Argument(value="channel", nullDefault=true) TextChannel channel, @Argument(value="role | user", endless=true) IPermissionHolder holder) {
		TextChannel effectiveChannel = channel == null ? event.getTextChannel() : channel;
		boolean role = holder instanceof Role;

		PermissionOverride override = effectiveChannel.getPermissionOverride(holder);
		if (override != null && override.getDenied().contains(Permission.MESSAGE_WRITE)) {
			event.replyFailure("That " + (role ? "role" : "user") + " is already muted in " + effectiveChannel.getAsMention()).queue();
			return;
		}

		effectiveChannel.upsertPermissionOverride(holder).deny(Permission.MESSAGE_WRITE)
			.flatMap($ -> event.replySuccess((role ? ((Role) holder).getAsMention() : "**" + ((Member) holder).getUser().getAsTag() + "**") + " is now muted in " + effectiveChannel.getAsMention()))
			.queue();
	}

	@Command(value="unmute", description="Unmute a user or role from a channel")
	@CommandId(336)
	@Examples({"channel unmute @Shea#6653", "channel unmute @Role", "channel unmute #channel @Shea#6653"})
	@AuthorPermissions(permissions={Permission.MANAGE_PERMISSIONS})
	@BotPermissions(permissions={Permission.MANAGE_PERMISSIONS})
	public void unmute(Sx4CommandEvent event, @Argument(value="channel", nullDefault=true) TextChannel channel, @Argument(value="role | user", endless=true) IPermissionHolder holder) {
		TextChannel effectiveChannel = channel == null ? event.getTextChannel() : channel;
		boolean role = holder instanceof Role;

		PermissionOverride override = effectiveChannel.getPermissionOverride(holder);
		if (override == null || !override.getDenied().contains(Permission.MESSAGE_WRITE)) {
			event.replyFailure("That " + (role ? "role" : "user") + " is not muted in " + effectiveChannel.getAsMention()).queue();
			return;
		}

		effectiveChannel.upsertPermissionOverride(holder).clear(Permission.MESSAGE_WRITE)
			.flatMap($ -> event.replySuccess((role ? ((Role) holder).getAsMention() : "**" + ((Member) holder).getUser().getAsTag() + "**") + " is no longer muted in " + effectiveChannel.getAsMention()))
			.queue();
	}

}
