package com.sx4.bot.commands.mod;

import com.jockie.bot.core.argument.Argument;
import com.sx4.bot.category.ModuleCategory;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.TextChannel;

public class LockdownCommand extends Sx4Command {

	public LockdownCommand() {
		super("lockdown", 223);

		super.setDescription("Locks down a specific channel, makes it so no one can send messages in the channel");
		super.setExamples("lockdown", "lockdown #channel");
		super.setCategoryAll(ModuleCategory.MODERATION);
		super.setAuthorDiscordPermissions(Permission.MANAGE_PERMISSIONS);
		super.setBotDiscordPermissions(Permission.MANAGE_PERMISSIONS);
	}

	public void onCommand(Sx4CommandEvent event, @Argument(value="channel", endless=true, nullDefault=true) TextChannel textChannel) {
		TextChannel channel = textChannel == null ? event.getTextChannel() : textChannel;

		Role role = event.getGuild().getPublicRole();
		PermissionOverride override = channel.getPermissionOverride(role);

		if (override != null && override.getDenied().contains(Permission.MESSAGE_WRITE)) {
			channel.upsertPermissionOverride(role).clear(Permission.MESSAGE_WRITE)
				.flatMap($ -> event.replySuccess(channel.getAsMention() + " is no longer locked down"))
				.queue();
		} else {
			channel.upsertPermissionOverride(role).deny(Permission.MESSAGE_WRITE)
				.flatMap($ -> event.replySuccess(channel.getAsMention() + " is now locked down"))
				.queue();
		}
	}

}
