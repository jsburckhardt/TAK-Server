package com.bbn.marti.takcl.connectivity.server;

import com.bbn.marti.takcl.TAKCLCore;
import com.bbn.marti.test.shared.data.servers.AbstractServerProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum ServerProcessDefinition {
	PluginManager("plugins", !TAKCLCore.disablePluginManagerProcess, "takserver-pm.jar", "logs/takserver-plugins.log",
			Collections.unmodifiableList(Arrays.asList(
					"t.s.p.s.DistributedPluginManager - execute method DistributedPluginManager",
					"t.s.plugins.service.PluginService - Started PluginService"
			)), null),

	RetentionService("retention", !TAKCLCore.disableRetentionProcess, "takserver-retention.jar", "logs/takserver-retention.log",
			Collections.unmodifiableList(Arrays.asList(
					"t.s.r.c.DistributedRetentionPolicyConfig -  execute method DistributedRetentionPolicyConfig",
					"t.s.retention.RetentionApplication - Started RetentionApplication"
			)), null),

	MessagingService("messaging", true, "takserver.war", "logs/takserver-messaging.log",
			Collections.unmodifiableList(Arrays.asList(
					"c.b.m.s.DistributedSubscriptionManager - DistributedSubscriptionManager execute",
					" c.b.m.s.DistributedConfiguration - execute method DistributedConfiguration",
					"t.s.f.DistributedFederationManager - execute method DistributedFederationManager",
					"c.b.m.g.DistributedPersistentGroupManager - execute method DistributedPersistentGroupManager",
					"t.s.profile.DistributedServerInfo - execute method DistributedServerInfo",
					"c.b.m.s.DistributedContactManager - execute method DistributedContactManager",
					"c.b.m.r.DistributedRepeaterManager - execute method DistributedRepeaterManager",
					"c.b.m.groups.DistributedUserManager - DistributedUserManager execute",
					"t.s.config.DistributedSystemInfoApi - execute method DistributedSystemInfoApi",
					"t.s.cluster.DistributedInputManager - execute method DistributedInputManager",
					"t.s.c.DistributedSecurityManager - execute method DistributedSecurityManager",
					"c.b.m.DistributedMetricsCollector - execute method DistributedMetricsCollector",
					"t.s.c.DistributedInjectionService - execute method DistributedInjectionService",
					"t.s.m.DistributedPluginDataFeedApi - execute method DistributedPluginDataFeedApi",
					"t.s.messaging.DistributedPluginApi - execute method DistributedPluginApi",
					"t.s.m.DistributedPluginSelfStopApi - execute method DistributedPluginSelfStopApi",
					"c.b.m.service.MessagingInitializer - takserver-core init complete"
			)), "-Dspring.profiles.active=messaging"),

	ApiService("api", !TAKCLCore.disableApiProcess, "takserver.war", "logs/takserver-api.log",
			Collections.unmodifiableList(Arrays.asList(
					"c.b.m.s.DistributedFederationHttpConnectorManager - execute method DistributedFederationHttpConnectorManager",
					"c.b.m.s.DistributedRetentionQueryManager - execute method DistributedRetentionQueryManager",
					"t.s.api.DistributedPluginMissionApi - execute method DistributedPluginMissionApi",
					"o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started"
			)), "-Dspring.profiles.active=api");

	public final String identifier;
	public final String jarName;
	public final String logPath;
	public final List<String> logWatchValues;
	public final String modeFlag;
	private boolean enabled;

	ServerProcessDefinition(@NotNull String identifier, boolean enabled, @NotNull String jarName,
	                        @NotNull String logPath, @NotNull List<String> logWatchValues, @Nullable String modeFlag) {
		this.identifier = identifier;
		this.enabled = enabled;
		this.jarName = jarName;
		this.logPath = logPath;
		this.logWatchValues = logWatchValues;
		this.modeFlag = modeFlag;
	}

	public void setEnabled(boolean value) {
		enabled = value;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public final List<String> waitForMissingLogStatements(@NotNull AbstractServerProfile serverIdentifier,
	                                                       @NotNull Path takserverLogsPath, int maxWaitDuration) {
		boolean serverReady = false;
		int duration = 0;
		List<String> remainingStatementsToSee = new ArrayList<>(this.logWatchValues);

		File logFile = takserverLogsPath.resolve(this.logPath).toFile();

		try {
			while (!serverReady && duration < maxWaitDuration) {
				if (!logFile.exists()) {
					Thread.sleep(500);
					duration += 500;
					continue;
				}
				BufferedReader logFileReader = new BufferedReader(new FileReader(logFile));

				List<String> statementsToRemove = new ArrayList<>(remainingStatementsToSee.size());

				String logLine = logFileReader.readLine();

				while (logLine != null) {
					for (String value : remainingStatementsToSee) {
						if (logLine.contains(value)) {
							statementsToRemove.add(value);
						}
					}
					logLine = logFileReader.readLine();
				}

				for (String value : statementsToRemove) {
					remainingStatementsToSee.remove(value);
				}
				statementsToRemove.clear();

				if (remainingStatementsToSee.isEmpty()) {
					serverReady = true;
				} else {
					Thread.sleep(500);
					duration += 500;
				}
			}

			if (TAKCLCore.serverStartupWaitTime != null && duration < TAKCLCore.serverStartupWaitTime) {
				System.out.println("Sleeping for " + (TAKCLCore.serverStartupWaitTime - duration) + " minutes.");
				Thread.sleep(TAKCLCore.serverStartupWaitTime - duration);
			}

			if (serverReady) {
				System.out.println("Server process " + this.identifier + " appears to be ready based on log statements after " + duration + " ms");
			} else {
				System.out.println("Server process " + this.identifier + " init timeout of " + maxWaitDuration + " ms reached. The following log statements were not seen:\n\t" +
						String.join("\"\n\t\"", remainingStatementsToSee) + "\n There is a good chance the tests may fail!");
			}
		} catch (InterruptedException | IOException e) {
			throw new RuntimeException(e);
		}
		return remainingStatementsToSee;
	}
}
