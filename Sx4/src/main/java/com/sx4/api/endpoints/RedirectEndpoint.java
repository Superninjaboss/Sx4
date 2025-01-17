package com.sx4.api.endpoints;

import com.sx4.bot.core.Sx4;
import org.bson.Document;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;

@Path("")
public class RedirectEndpoint {

	private static final String ALPHA_NUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

	private final SecureRandom random = new SecureRandom();

	private final Sx4 bot;

	public RedirectEndpoint(Sx4 bot) {
		this.bot = bot;
	}

	private String getAlphaNumericId(int length) {
		StringBuilder id = new StringBuilder();
		for (int i = 0; i < length; i++) {
			id.append(ALPHA_NUMERIC.charAt(this.random.nextInt(ALPHA_NUMERIC.length())));
		}

		return id.toString();
	}

	@GET
	@Path("{id: [a-zA-Z0-9]{2,7}}")
	public Response getRedirect(@PathParam("id") final String id) {
		Document redirect = this.bot.getMongoMain().getRedirectById(id);
		if (redirect == null) {
			return Response.status(404).build();
		}

		return Response.status(Response.Status.MOVED_PERMANENTLY).location(URI.create(redirect.getString("url"))).build();
	}

	@POST
	@Path("api/shorten")
	@Produces(MediaType.APPLICATION_JSON)
	public void postRedirect(final String body, @Suspended final AsyncResponse response) {
		Document json = Document.parse(body);

		String url = json.getString("url");
		if (url == null) {
			response.resume(Response.status(400).build());
			return;
		}

		try {
			new URL(url);
		} catch (MalformedURLException e) {
			response.resume(Response.status(400).build());
			return;
		}

		Document result;
		String id;
		do {
			id = this.getAlphaNumericId(7);
			result = this.bot.getMongoMain().getRedirectById(id);
		} while (result != null);

		this.bot.getMongoMain().insertRedirect(id, url).whenComplete((data, exception) -> {
			if (exception != null) {
				response.resume(exception);
				return;
			}

			response.resume(Response.ok(data.toJson()).build());
		});
	}

}
