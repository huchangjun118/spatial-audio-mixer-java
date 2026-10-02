package com.github.huchangjun118.spatialaudiomixerjava.service.dto;

/**
 * Request to add or update an audio source.
 *
 * @param id              optional id (generated if absent)
 * @param position        world position
 * @param gain            linear gain
 * @param refDistance     distance at which attenuation starts
 * @param maxDistance     distance at which the source is inaudible
 * @param rolloff         attenuation steepness
 * @param reverbSend      reverb send amount (0..1)
 * @param waveform        one of: sine, square, sawtooth, noise, silence
 * @param frequency       waveform frequency in Hz
 * @param durationSeconds source duration (ignored for silence)
 * @param loop            whether the source loops
 */
public record SourceRequest(
        String id,
        Vector3Dto position,
        Float gain,
        Float refDistance,
        Float maxDistance,
        Float rolloff,
        Float reverbSend,
        String waveform,
        Double frequency,
        Double durationSeconds,
        Boolean loop) {
}
