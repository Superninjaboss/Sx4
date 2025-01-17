package com.sx4.bot.commands.info;

import com.sx4.bot.category.ModuleCategory;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.paged.PagedResult;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Emote;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ServerEmotesCommand extends Sx4Command {

	public ServerEmotesCommand() {
		super("server emotes", 277);

		super.setDescription("View all the emotes in the current server");
		super.setAliases("serveremotes", "server emojis", "serveremojis");
		super.setExamples("server emotes");
		super.setBotDiscordPermissions(Permission.MESSAGE_EMBED_LINKS);
		super.setCategoryAll(ModuleCategory.INFORMATION);
	}

	public void onCommand(Sx4CommandEvent event) {
		List<Emote> emotes = event.getGuild().getEmoteCache().applyStream(stream -> stream.sorted(Comparator.comparing(Emote::isAnimated)).collect(Collectors.toList()));

		PagedResult<Emote> paged = new PagedResult<>(event.getBot(), emotes)
			.setIndexed(false)
			.setPerPage(15)
			.setAuthor("Emotes", null, event.getGuild().getIconUrl())
			.setDisplayFunction(emote -> (event.getSelfMember().canInteract(emote) ? emote.getAsMention() : "[:" + emote.getName() + ":](" + emote.getImageUrl() + ")") + " - " + emote.getName());

		paged.execute(event);
	}

}
