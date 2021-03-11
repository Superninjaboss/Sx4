package com.sx4.bot.handlers;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbed.EmbedAuthor;
import club.minnced.discord.webhook.send.WebhookEmbed.EmbedField;
import club.minnced.discord.webhook.send.WebhookEmbed.EmbedFooter;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import com.sx4.bot.core.Sx4;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDA.ShardInfo;
import net.dv8tion.jda.api.events.DisconnectEvent;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.ReconnectedEvent;
import net.dv8tion.jda.api.events.ResumedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;

import java.time.Instant;
import java.time.OffsetDateTime;

public class ConnectionHandler extends ListenerAdapter {

	private final WebhookClient eventsWebhook;

	private final Sx4 bot;

	public ConnectionHandler(Sx4 bot) {
		this.bot = bot;

		this.eventsWebhook = new WebhookClientBuilder(this.bot.getConfig().getEventsWebhookId(), this.bot.getConfig().getEventsWebhookToken())
			.setHttpClient(this.bot.getHttpClient())
			.build();
	}
	
	private int readyEventsCalled = 0;
	
	public WebhookEmbed getEmbed(JDA jda, String state, int colour) {
		return this.getEmbed(jda, state, null, null, colour);
	}
	
	public WebhookEmbed getEmbed(JDA jda, String state, CloseCode closeCode, OffsetDateTime disconnectTime, int colour) {
		ShardInfo shardInfo = jda.getShardInfo();
		
		WebhookEmbedBuilder builder = new WebhookEmbedBuilder()
			.setColor(colour)
			.setFooter(new EmbedFooter(state, null))
			.setTimestamp(disconnectTime != null ? disconnectTime : Instant.now())
			.addField(new EmbedField(false, "Shard", (shardInfo.getShardId() + 1) + "/" + shardInfo.getShardTotal()))
			.setAuthor(new EmbedAuthor(jda.getSelfUser().getAsTag(), jda.getSelfUser().getEffectiveAvatarUrl(), null));
		
		if (closeCode != null) {
			builder.addField(new EmbedField(false, "Reason", closeCode.getMeaning() + " [" + closeCode.getCode() + "]"));
		}
		
		return builder.build();
	}

	public void onReady(ReadyEvent event) {
		JDA jda = event.getJDA();
		
		if (++this.readyEventsCalled == jda.getShardInfo().getShardTotal()) {
			this.bot.getReminderManager().ensureReminders();
			this.bot.getMuteManager().ensureMutes();
			this.bot.getTemporaryBanManager().ensureBans();
			this.bot.getGiveawayManager().ensureGiveaways();
			this.bot.getAntiRegexManager().ensureAttempts();
			this.bot.getPremiumManager().ensurePremiumExpiry();
		}
		
		this.eventsWebhook.send(this.getEmbed(jda, "Ready", this.bot.getConfig().getGreen()));
	}
	
	public void onReconnected(ReconnectedEvent event) {
		this.eventsWebhook.send(this.getEmbed(event.getJDA(), "Reconnect", this.bot.getConfig().getGreen()));
	}
	
	public void onResumed(ResumedEvent event) {
		this.eventsWebhook.send(this.getEmbed(event.getJDA(), "Resume", this.bot.getConfig().getGreen()));
	}
	
	public void onDisconnect(DisconnectEvent event) {
		this.eventsWebhook.send(this.getEmbed(event.getJDA(), "Disconnect", event.getCloseCode(), event.getTimeDisconnected(), this.bot.getConfig().getRed()));
	}
	
}
