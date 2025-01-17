package com.sx4.bot.handlers;

import com.sx4.bot.core.Sx4;
import com.sx4.bot.entities.info.ServerStatsType;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.EventListener;

public class ServerStatsHandler implements EventListener {

	private final Sx4 bot;

	public ServerStatsHandler(Sx4 bot) {
		this.bot = bot;
	}

	public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
		if (event.getAuthor().isBot()) {
			return;
		}

		this.bot.getServerStatsManager().incrementCounter(event.getGuild().getIdLong(), ServerStatsType.MESSAGES);
	}

	public void onGuildMemberJoin(GuildMemberJoinEvent event) {
		if (event.getUser().isBot()) {
			return;
		}

		this.bot.getServerStatsManager().incrementCounter(event.getGuild().getIdLong(), ServerStatsType.JOINS);
	}

	public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
		if (event.getUser().isBot()) {
			return;
		}

		this.bot.getServerStatsManager().decrementCounter(event.getGuild().getIdLong(), ServerStatsType.JOINS);
	}


	@Override
	public void onEvent(GenericEvent event) {
		if (event instanceof GuildMessageReceivedEvent) {
			this.onGuildMessageReceived((GuildMessageReceivedEvent) event);
		} else if (event instanceof GuildMemberJoinEvent) {
			this.onGuildMemberJoin((GuildMemberJoinEvent) event);
		} else if (event instanceof GuildMemberRemoveEvent) {
			this.onGuildMemberRemove((GuildMemberRemoveEvent) event);
		}
	}

}
