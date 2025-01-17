package com.sx4.bot.commands.info;

import com.jockie.bot.core.argument.Argument;
import com.sx4.bot.cache.GoogleSearchCache.GoogleSearchResult;
import com.sx4.bot.category.ModuleCategory;
import com.sx4.bot.core.Sx4Command;
import com.sx4.bot.core.Sx4CommandEvent;
import com.sx4.bot.paged.PagedResult;
import com.sx4.bot.utility.ExceptionUtility;
import com.sx4.bot.utility.StringUtility;
import net.dv8tion.jda.api.Permission;

import javax.ws.rs.ForbiddenException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

public class GoogleCommand extends Sx4Command {

	public GoogleCommand() {
		super("google", 208);

		super.setDescription("Searches a query up on google");
		super.setExamples("google How to use Sx4", "google Sx4 discord bot");
		super.setBotDiscordPermissions(Permission.MESSAGE_EMBED_LINKS);
		super.setCategoryAll(ModuleCategory.INFORMATION);
	}

	public void onCommand(Sx4CommandEvent event, @Argument(value="query", endless=true) String query) {
		boolean nsfw = event.getTextChannel().isNSFW();

		event.getBot().getGoogleCache().retrieveResultsByQuery(query, nsfw).whenComplete((results, exception) -> {
			Throwable cause = exception instanceof CompletionException ? exception.getCause() : exception;
			if (cause instanceof ForbiddenException) {
				event.replyFailure(cause.getMessage()).queue();
				return;
			}

			if (ExceptionUtility.sendExceptionally(event, exception)) {
				return;
			}

			String googleUrl = "https://www.google.co.uk/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + (nsfw ? "" : "&safe=active");

			PagedResult<GoogleSearchResult> paged = new PagedResult<>(event.getBot(), results)
				.setIndexed(false)
				.setPerPage(3)
				.setAuthor("Google", googleUrl, "http://i.imgur.com/G46fm8J.png")
				.setDisplayFunction(result -> {
					String title = result.getTitle(), link = result.getLink(), snippet = result.getSnippet();

					return "**[" + StringUtility.limit(title, 32) + "](" + link + ")**\n" + StringUtility.limit(snippet, 246, " ...") + "\n";
				});

			paged.execute(event);
		});
	}

}
