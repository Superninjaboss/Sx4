package com.sx4.bot.core;

import com.jockie.bot.core.command.ICommand;
import com.jockie.bot.core.command.ICommand.ArgumentParsingType;
import com.jockie.bot.core.command.impl.CommandEvent;
import com.jockie.bot.core.command.impl.CommandListener;
import com.sx4.bot.config.Config;
import com.sx4.bot.database.mongo.MongoDatabase;
import com.sx4.bot.database.postgres.PostgresDatabase;
import com.sx4.bot.utility.CheckUtility;
import com.sx4.bot.utility.HelpUtility;
import com.sx4.bot.utility.MathUtility;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import okhttp3.OkHttpClient;

import java.security.SecureRandom;
import java.util.Map;

public class Sx4CommandEvent extends CommandEvent {

	private final Sx4 bot;

	public Sx4CommandEvent(Sx4 bot, Message message, CommandListener listener, ICommand command,
			Object[] arguments, String[] rawArguments, String prefix, String commandTrigger, 
			Map<String, Object> options, ArgumentParsingType parsingType, String contentOverflow, long timeStarted) {
		super(message, listener, command, arguments, rawArguments, prefix, commandTrigger, options, parsingType, contentOverflow, timeStarted);

		this.bot = bot;
	}

	public Sx4 getBot() {
		return this.bot;
	}

	public Config getConfig() {
		return this.bot.getConfig();
	}

	public OkHttpClient getHttpClient() {
		return this.bot.getHttpClient();
	}
	
	public Sx4Command getCommand() {
		return (Sx4Command) super.getCommand();
	}

	public SecureRandom getRandom() {
		return MathUtility.RANDOM;
	}
	
	public Sx4CommandListener getCommandListener() {
		return (Sx4CommandListener) this.commandListener;
	}
	
	public MongoDatabase getMongo() {
		return this.bot.getMongo();
	}

	public MongoDatabase getMongoMain() {
		return this.bot.getMongoMain();
	}

	public MongoDatabase getMongoCanary() {
		return this.bot.getMongoCanary();
	}

	public PostgresDatabase getPostgres() {
		return this.bot.getPostgres();
	}

	public PostgresDatabase getPostgresMain() {
		return this.bot.getPostgresMain();
	}

	public PostgresDatabase getPostgresCanary() {
		return this.bot.getPostgresCanary();
	}

	public boolean hasPermission(Permission... permissions) {
		return this.getSelfMember().hasPermission(this.getTextChannel(), permissions);
	}

	public boolean hasPermission(Member member, Permission... permissions) {
		return CheckUtility.hasPermissions(this.bot, member, this.getTextChannel(), this.getProperty("fakePermissions"), permissions);
	}
	
	public MessageAction replyHelp() {
		return this.reply(HelpUtility.getHelpMessage(this.command, !this.isFromGuild() || this.getSelfMember().hasPermission(this.getTextChannel(), Permission.MESSAGE_EMBED_LINKS)));
	}

	public MessageAction replyTimed(long start) {
		return this.replyFormat("%,.3fms :stopwatch:", (System.nanoTime() - start) / 1_000_000D);
	}

	public MessageAction replySuccess(String content) {
		return this.reply(content + " " + this.getConfig().getSuccessEmote());
	}

	public MessageAction replyFailure(String content) {
		return this.reply(content + " " + this.getConfig().getFailureEmote());
	}
	
}
