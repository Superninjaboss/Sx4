package com.sx4.bot.commands.mod;

import java.util.function.Predicate;

import com.jockie.bot.core.argument.Argument;
import com.jockie.bot.core.utility.function.TriConsumer;
import com.sx4.bot.category.Category;
import com.sx4.bot.core.Sx4Bot;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.entities.mod.PartialEmote;
import com.sx4.bot.http.HttpCallback;
import com.sx4.bot.utility.ExceptionUtility;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import okhttp3.Request;

public class CreateEmoteCommand extends Sx4Command {
	
	public CreateEmoteCommand() {
		super("create emote");
		
		super.setDescription("Creates an emote from a url, emote mention or emote name");
		super.setAliases("createemote", "ce");
		super.setExamples("create emote <:sx4:637715282995183636>", "create emote sx4", "create emote https://cdn.discordapp.com/emojis/637715282995183636.png?v=1");
		super.setCategory(Category.MODERATION);
		super.setCooldownDuration(5);
		super.setAuthorDiscordPermissions(Permission.MANAGE_EMOTES);
		super.setBotDiscordPermissions(Permission.MANAGE_EMOTES);
	}
	
	private void getBytes(String url, TriConsumer<byte[], Boolean, Integer> bytes) {
		Request request = new Request.Builder()
			.url(url)
			.build();
		
		Sx4Bot.getClient().newCall(request).enqueue((HttpCallback) response -> {
			if (response.code() == 200) {
				String contentType = response.header("Content-Type");
				String extension = null;
				if (contentType.contains("/")) {
					extension = contentType.split("/")[1].toLowerCase();
				}
				
				bytes.accept(response.body().bytes(), extension == null ? null : extension.equals("gif"), 200);
				return;
			} else if (response.code() == 415) {
				int periodIndex = url.lastIndexOf('.') + 1;
				if (periodIndex != -1) {
					String extension = url.substring(periodIndex);
					
					if (extension.equalsIgnoreCase("gif")) {
						this.getBytes(url.substring(0, periodIndex) + "png", bytes);
						return;
					}
				}
			}
				
			bytes.accept(null, null, response.code());
		});
	}
	
	public void onCommand(Sx4CommandEvent event, @Argument(value="emote", endless=true, acceptEmpty=true) PartialEmote emote) {
		long animatedEmotes = event.getGuild().getEmoteCache().stream().filter(Emote::isAnimated).count();
		long nonAnimatedEmotes = event.getGuild().getEmoteCache().stream().filter(Predicate.not(Emote::isAnimated)).count();
		int maxEmotes = event.getGuild().getMaxEmotes();
		
		Boolean animated = emote.isAnimated();
		if (animated != null && ((animated && animatedEmotes >= maxEmotes) || (!animated && nonAnimatedEmotes >= maxEmotes))) {
			event.reply("You already have the max" + (animated ? "" : " non") + " animated emotes on this server :no_entry:").queue();
			return;
		}
		
		this.getBytes(emote.getUrl(), (bytes, animatedResponse, code) -> {
			if (bytes == null) {
				event.reply("Failed to get url from the emote argument with status code: " + code + " :no_entry:").queue();
				return;
			}
			
			if (animatedResponse != null && ((animatedResponse && animatedEmotes >= maxEmotes) || (!animatedResponse && nonAnimatedEmotes >= maxEmotes))) {
				event.reply("You already have the max" + (animated ? "" : " non") + " animated emotes on this server :no_entry:").queue();
				return;
			}
			
			event.getGuild().createEmote(emote.hasName() ? emote.getName() : "Unnamed_Emote", Icon.from(bytes))
				.flatMap(createdEmote -> event.reply(createdEmote.getAsMention() + " has been created <:done:403285928233402378>"))
				.queue(null, exception -> {
					if (exception instanceof ErrorResponseException && ((ErrorResponseException) exception).getErrorCode() == 400) {
						event.reply("You cannot create an emote larger than 256KB :no_entry:").queue();
						return;
					}
					
					ExceptionUtility.sendExceptionally(event, exception);
				});
		});
	}

}
