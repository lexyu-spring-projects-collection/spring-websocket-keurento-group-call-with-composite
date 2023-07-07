package com.lex.pr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Room implements Closeable {
	private final Logger log = LoggerFactory.getLogger(Room.class);

	private final ConcurrentMap<String, UserSession> participants = new ConcurrentHashMap<>();
	private final MediaPipeline pipeline;
	private final String name;
	private final Composite composite;
	private RecorderEndpoint recorderAudioEndpoint;
	private RecorderEndpoint recorderVideoEndpoint;
	private HubPort compositeOutputHubport;
	public static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-S");
	public static final String RECORDING_PATH = "file:///tmp/" + df.format(new Date());
	private static final String RECORDER_FILE_EXT = ".mp4";

	public String getName() {
		return name;
	}

	public Room(String roomName, MediaPipeline pipeline) {
		this.name = roomName;
		this.pipeline = pipeline;
		this.composite = new Composite.Builder(pipeline).build();
		this.compositeOutputHubport = new HubPort.Builder(composite).build();
		log.info("ROOM {} has been created", roomName);
	}

	@PreDestroy
	private void shutdown() {
		this.close();
	}

	public UserSession join(String userName, WebSocketSession session) throws IOException {
		log.info("ROOM {}: adding participant {}", this.name, userName);
		final UserSession participant = new UserSession(userName, this.name, session, this.pipeline,
				this.composite, this.compositeOutputHubport);

		if (participants.size() == 0) {
			this.recorderAudioEndpoint = new RecorderEndpoint.Builder(this.pipeline,
					RECORDING_PATH + "-" + name + "-audio" + ".mp4")
					.withMediaProfile(MediaProfileSpecType.MP4_AUDIO_ONLY)
					.build();

			this.recorderVideoEndpoint = new RecorderEndpoint.Builder(this.pipeline,
					RECORDING_PATH + "-" + name + "-video" + ".webm")
					.withMediaProfile(MediaProfileSpecType.WEBM_VIDEO_ONLY)
					.build();

//			recorderAudioEndpoint.setMinEncoderBitrate();
			recorderAudioEndpoint.setMaxEncoderBitrate(0);

			this.compositeOutputHubport.connect(this.recorderAudioEndpoint);

			participant.getOutgoingWebRtcPeer().connect(this.recorderVideoEndpoint);

			this.recorderAudioEndpoint.addRecordingListener(new EventListener<RecordingEvent>() {
				@Override
				public void onEvent(RecordingEvent event) {
					log.info("User " + userName + " is recording");
				}

			});
			this.recorderAudioEndpoint.addStoppedListener(new EventListener<StoppedEvent>() {

				@Override
				public void onEvent(StoppedEvent event) {
					log.info("User " + userName + " is stopped");
				}

			});
			this.recorderAudioEndpoint.addPausedListener(new EventListener<PausedEvent>() {

				@Override
				public void onEvent(PausedEvent event) {
					log.info("User " + userName + " is paused");
				}

			});

			this.recorderVideoEndpoint.addRecordingListener(new EventListener<RecordingEvent>() {

				@Override
				public void onEvent(RecordingEvent event) {
					log.info("User " + userName + " is recording");
				}

			});
			this.recorderVideoEndpoint.addStoppedListener(new EventListener<StoppedEvent>() {

				@Override
				public void onEvent(StoppedEvent event) {
					log.info("User " + userName + " is stopped");
				}

			});
			this.recorderVideoEndpoint.addPausedListener(new EventListener<PausedEvent>() {

				@Override
				public void onEvent(PausedEvent event) {
					log.info("User " + userName + " is paused");
				}

			});

			this.recorderVideoEndpoint.record();
			this.recorderAudioEndpoint.record();
		}
		joinRoom(participant);
		participants.put(participant.getName(), participant);
		sendParticipantNames(participant);
		return participant;
	}

	public void leave(UserSession user) throws IOException {
		log.debug("PARTICIPANT {}: Leaving room {}", user.getName(), this.name);
		this.removeParticipant(user.getName());
		user.close();
	}

	private Collection<String> joinRoom(UserSession newParticipant) throws IOException {
		final JsonObject newParticipantMsg = new JsonObject();
		newParticipantMsg.addProperty("id", "newParticipantArrived");
		newParticipantMsg.addProperty("name", newParticipant.getName());

		final List<String> participantsList = new ArrayList<>(participants.values().size());
		log.debug("ROOM {}: notifying other participants of new participant {}", name,
				newParticipant.getName());

		for (final UserSession participant : participants.values()) {
			try {
				participant.sendMessage(newParticipantMsg);
			} catch (final IOException e) {
				log.debug("ROOM {}: participant {} could not be notified", name, participant.getName(), e);
			}
			participantsList.add(participant.getName());
		}

		return participantsList;
	}

	private void removeParticipant(String name) throws IOException {
		participants.remove(name);

		log.debug("ROOM {}: notifying all users that {} is leaving the room", this.name, name);

		final List<String> unnotifiedParticipants = new ArrayList<>();
		final JsonObject participantLeftJson = new JsonObject();
		participantLeftJson.addProperty("id", "participantLeft");
		participantLeftJson.addProperty("name", name);
		for (final UserSession participant : participants.values()) {
			try {
				participant.cancelVideoFrom(name);
				participant.sendMessage(participantLeftJson);
			} catch (final IOException e) {
				unnotifiedParticipants.add(participant.getName());
			}
		}

		if (!unnotifiedParticipants.isEmpty()) {
			log.debug("ROOM {}: The users {} could not be notified that {} left the room", this.name,
					unnotifiedParticipants, name);
		}

	}

	public void sendParticipantNames(UserSession user) throws IOException {

		final JsonArray participantsArray = new JsonArray();
		for (final UserSession participant : this.getParticipants()) {
			if (!participant.equals(user)) {
				final JsonElement participantName = new JsonPrimitive(participant.getName());
				participantsArray.add(participantName);
			}
		}

		final JsonObject existingParticipantsMsg = new JsonObject();
		existingParticipantsMsg.addProperty("id", "existingParticipants");
		existingParticipantsMsg.add("data", participantsArray);
		log.debug("PARTICIPANT {}: sending a list of {} participants", user.getName(),
				participantsArray.size());
		user.sendMessage(existingParticipantsMsg);
	}

	public Collection<UserSession> getParticipants() {
		return participants.values();
	}

	public UserSession getParticipant(String name) {
		return participants.get(name);
	}

	@Override
	public void close() {
		for (final UserSession user : participants.values()) {
			try {
				user.close();
			} catch (IOException e) {
				log.debug("ROOM {}: Could not invoke close on participant {}", this.name, user.getName(),
						e);
			}
		}

		participants.clear();

		pipeline.release(new Continuation<Void>() {

			@Override
			public void onSuccess(Void result) throws Exception {
				log.trace("ROOM {}: Released Pipeline", Room.this.name);
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("PARTICIPANT {}: Could not release Pipeline", Room.this.name);
			}
		});

		log.debug("Room {} closed", this.name);
	}

}
