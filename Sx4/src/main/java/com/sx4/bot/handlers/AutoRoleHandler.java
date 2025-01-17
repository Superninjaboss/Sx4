package com.sx4.bot.handlers;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.sx4.bot.core.Sx4;
import com.sx4.bot.database.mongo.MongoDatabase;
import com.sx4.bot.utility.AutoRoleUtility;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdatePendingEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AutoRoleHandler implements EventListener {

	private final Sx4 bot;

	public AutoRoleHandler(Sx4 bot) {
		this.bot = bot;
	}

	public void updateAutoRoles(Guild guild, Member member) {
		if (member.isPending()) {
			return;
		}

		Member selfMember = guild.getSelfMember();
		if (!selfMember.hasPermission(Permission.MANAGE_ROLES)) {
			return;
		}

		List<Document> autoRoles = this.bot.getMongo().getAutoRoles(Filters.eq("guildId", guild.getIdLong()), Projections.include("enabled", "filters", "roleId")).into(new ArrayList<>());
		if (autoRoles.isEmpty()) {
			return;
		}

		List<Role> roles = new ArrayList<>();
		for (Document autoRole : autoRoles) {
			if (!autoRole.get("enabled", true)) {
				continue;
			}

			Role role = guild.getRoleById(autoRole.getLong("roleId"));
			if (role == null || !selfMember.canInteract(role)) {
				continue;
			}

			List<Document> filters = autoRole.getList("filters", Document.class, Collections.emptyList());
			if (AutoRoleUtility.filtersMatch(member, filters)) {
				roles.add(role);
			}
		}

		if (!roles.isEmpty()) {
			guild.modifyMemberRoles(member, roles, null).reason("Auto Roles").queue();
		}
	}
	
	public void onGuildMemberJoin(GuildMemberJoinEvent event) {
		this.updateAutoRoles(event.getGuild(), event.getMember());
	}

	public void onGuildMemberUpdatePending(GuildMemberUpdatePendingEvent event) {
		this.updateAutoRoles(event.getGuild(), event.getMember());
	}
	
	public void onRoleDelete(RoleDeleteEvent event) {
		this.bot.getMongo().deleteAutoRole(Filters.eq("roleId", event.getRole().getIdLong())).whenComplete(MongoDatabase.exceptionally(this.bot.getShardManager()));
	}

	@Override
	public void onEvent(GenericEvent event) {
		if (event instanceof GuildMemberJoinEvent) {
			this.onGuildMemberJoin((GuildMemberJoinEvent) event);
		} else if (event instanceof RoleDeleteEvent) {
			this.onRoleDelete((RoleDeleteEvent) event);
		} else if (event instanceof GuildMemberUpdatePendingEvent) {
			this.onGuildMemberUpdatePending((GuildMemberUpdatePendingEvent) event);
		}
	}

}
