package dev.holoweb.vitrine.impl;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Plays a page's captured audio through an OpenAL streaming source in Minecraft's own context,
 * so it follows the game's volume sliders and (for world panels) its 3D listener.
 * Render thread only; refilled once per frame.
 */
final class AudioOut {
	private static final int BUFFERS = 8;
	private static final int CHUNK_MS = 20; // per buffer; ~60 ms queued before playback starts
	private static final int START_QUEUED = 3;

	private final long handle;
	private final boolean mono;
	private final SoundSource category;
	private final ArrayDeque<Integer> free = new ArrayDeque<>();
	private ByteBuffer chunk;
	private int source;

	AudioOut(long handle, boolean mono, SoundSource category) {
		this.handle = handle;
		this.mono = mono;
		this.category = category;
	}

	/**
	 * @param pos   world position (mono, attenuated over {@code range} blocks) or null (stereo, head-locked)
	 * @param gain  the page's own volume, 0..1, on top of the game's category volume
	 */
	void update(Vec3 pos, float gain, float range) {
		int rate = Native.audioRate(handle);
		if (rate == 0 && source == 0) return; // never made a sound
		if (source != 0 && !AL10.alIsSource(source)) source = 0; // sound engine reloaded (F3+T, device change)
		if (source == 0) open();

		int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
		for (int i = 0; i < processed; i++) free.add(AL10.alSourceUnqueueBuffers(source));
		// Ran dry (a slow frame): a stopped source counts every newly queued buffer as already played,
		// so it would never refill to START_QUEUED. Rewind = back to INITIAL, buffers count again.
		if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) AL10.alSourceRewind(source);

		var mc = Minecraft.getInstance();
		if (mc.isPaused()) {
			// Paused game: drop what the page produces instead of queuing it up for later.
			while (Native.readAudio(handle, MemoryUtil.memAddress(chunk), chunk.capacity()) > 0) {}
			AL10.alSourcePause(source);
			return;
		}

		if (rate > 0) {
			int bytes = rate * CHUNK_MS / 1000 * (mono ? 2 : 4);
			if (chunk.capacity() < bytes) chunk = MemoryUtil.memRealloc(chunk, bytes);
			int format = mono ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
			while (!free.isEmpty()) {
				int n = Native.readAudio(handle, MemoryUtil.memAddress(chunk), bytes);
				if (n > 0) queue(format, n, rate);
				if (n < bytes) break; // drained what CEF has produced so far
			}
		}

		//? if >=1.21.6 {
		float volume = mc.options.getFinalSoundSourceVolume(category) * gain;
		//?} else {
		/*float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
		float volume = (category == SoundSource.MASTER ? master : master * mc.options.getSoundSourceVolume(category)) * gain;
		*///?}
		AL10.alSourcef(source, AL10.AL_GAIN, volume);
		if (pos != null) {
			AL10.alSource3f(source, AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
			AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, range);
		}

		int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
		int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
		if (state != AL10.AL_PLAYING && queued >= START_QUEUED) AL10.alSourcePlay(source);
	}

	private void queue(int format, int bytes, int rate) {
		int buffer = free.poll();
		chunk.limit(bytes);
		AL10.alBufferData(buffer, format, chunk, rate);
		chunk.clear();
		AL10.alSourceQueueBuffers(source, buffer);
	}

	private void open() {
		source = AL10.alGenSources();
		free.clear();
		for (int i = 0; i < BUFFERS; i++) free.add(AL10.alGenBuffers());
		if (chunk == null) chunk = MemoryUtil.memAlloc(48000 * CHUNK_MS / 1000 * 4);
		if (mono) {
			// Same falloff as vanilla sounds (Channel.linearAttenuation): Minecraft enables per-source
			// distance models, so each source must pick one; otherwise it is not attenuated at all.
			AL10.alSourcei(source, AL10.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE);
			AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1);
			AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0);
		} else {
			AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // stays at the listener
			AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
		}
	}

	void close() {
		if (source != 0 && AL10.alIsSource(source)) {
			AL10.alSourceStop(source);
			int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
			for (int i = 0; i < queued; i++) free.add(AL10.alSourceUnqueueBuffers(source));
			AL10.alDeleteSources(source);
			for (int b : free) AL10.alDeleteBuffers(b);
		}
		source = 0;
		free.clear();
		if (chunk != null) MemoryUtil.memFree(chunk);
		chunk = null;
	}
}
