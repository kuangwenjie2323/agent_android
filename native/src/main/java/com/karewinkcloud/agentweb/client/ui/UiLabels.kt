package com.karewinkcloud.agentweb.client.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*

// Dialog windows supply their own Android resource locals. This app local keeps
// the selected language available in their child compositions too.
internal val LocalAppResources = staticCompositionLocalOf<Resources?> { null }

@Composable internal fun tr(id: Int, vararg args: Any): String {
    val resources = LocalAppResources.current
    return if (args.isEmpty()) resources?.getString(id) ?: stringResource(id)
        else resources?.getString(id, *args) ?: stringResource(id, *args)
}
@Composable internal fun countLabel(id: Int, count: Int): String =
    LocalAppResources.current?.getQuantityString(id, count, count) ?: pluralStringResource(id, count, count)
@Composable internal fun AppIcon(icon: Int, description: String? = null) {
    Icon(painterResource(icon), description, Modifier.size(20.dp))
}
@Composable internal fun ActionIcon(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp), enabled = enabled) { AppIcon(icon, label) }
}
@Composable internal fun permissionLabel(value: String) = tr(when (value) { "plan" -> S.permission_plan; "full" -> S.permission_full; else -> S.permission_auto })
@Composable internal fun effortLabel(value: String?) = tr(when(value) {
    "none" -> S.effort_none; "minimal" -> S.effort_minimal; "low" -> S.effort_low; "medium" -> S.effort_medium
    "high" -> S.effort_high; "xhigh" -> S.effort_xhigh; "max" -> S.effort_max; "ultra" -> S.effort_ultra; else -> S.effort_default
})
@Composable internal fun kindLabel(kind: String) = tr(when(kind) { "video" -> S.media_video; "audio" -> S.media_audio; else -> S.media_image })
@Composable internal fun modelHint(agent: Agent, model: String): String {
    val route = tr(if (agent.contextKinds[model] == "API" || agent.id in setOf("deepseek", "minimax")) S.route_api else S.route_cli)
    val count = agent.contextWindows[model] ?: return route
    val formatted = if (count >= 1_000_000) "${java.text.DecimalFormat("0.#").format(count / 1_000_000.0)}M" else "${count / 1000}k"
    return tr(S.context_hint, route, formatted)
}
@Composable internal fun conversationTitle(conversation: Conversation) =
    if (conversation.title.isBlank() || conversation.title.equals("New chat", true)) tr(S.new_chat) else conversation.title
@Composable internal fun comfyStatus(status: String) = tr(when(status) {
    "pending", "submitting" -> S.status_pending; "queued" -> S.status_queued; "running" -> S.status_running
    "succeeded" -> S.status_succeeded; "failed", "error" -> S.status_failed; "canceled" -> S.status_canceled
    "expired" -> S.status_expired; "query_failed" -> S.status_query_failed; else -> S.status_unknown
})
@Composable internal fun styleLabel(key: String) = tr(when (key) {
    "portrait" -> S.style_portrait; "fashion" -> S.style_fashion; "anime" -> S.style_anime; "cartoon3d" -> S.style_cartoon3d
    "cinematic" -> S.style_cinematic; "product" -> S.style_product; "poster" -> S.style_poster; "watercolor" -> S.style_watercolor
    "guofeng" -> S.style_guofeng; else -> S.style_cyberpunk
})
internal fun comfyChannelResource(channel: String) = when (channel) {
    "kaggle_gpu" -> S.channel_kaggle; "cloud_gpu" -> S.channel_gpu; "partner_api" -> S.channel_api
    "runpod_gpu" -> S.channel_runpod; else -> S.channel_unknown
}
internal fun comfyChannelHintResource(channel: String) = when (channel) {
    "kaggle_gpu" -> S.channel_kaggle_hint; "cloud_gpu" -> S.channel_gpu_hint; "partner_api" -> S.channel_api_hint
    "runpod_gpu" -> S.channel_runpod_hint; else -> S.channel_unknown
}
internal fun comfyProgressResource(job: ComfyJob) = if (job.status == "canceling") S.status_canceling else when (job.phase()) {
    ComfyJobPhase.STARTING -> if (job.billingChannel == "runpod_gpu") {
        if (job.workerStage == "waiting_gpu") S.status_runpod_waiting_gpu else S.status_runpod_starting
    } else when (job.workerStage) {
        "installing" -> S.status_kaggle_installing
        "models" -> S.status_kaggle_models
        "comfy", "ready", "first_image" -> S.status_kaggle_comfy
        else -> S.status_kaggle_starting
    }
    ComfyJobPhase.QUEUED -> S.status_queued
    ComfyJobPhase.FINISHING -> S.status_finishing
    else -> S.status_running
}
internal fun comfyErrorResource(code: String) = when(code) {
    "auth", "sign_in_required" -> S.error_auth; "forbidden" -> S.error_forbidden
    "job_active" -> S.error_job_active; "unpublish_failed" -> S.error_unpublish_failed
    "insufficient_credits" -> S.error_insufficient_credits
    "runpod_unavailable" -> S.error_runpod_unavailable; "runpod_rejected" -> S.error_runpod_rejected; "runpod_failed" -> S.error_runpod_failed
    "runpod_no_capacity" -> S.error_runpod_no_capacity
    "network", "timeout" -> S.error_network; "invalid_parameters", "invalid_request", "validation_error" -> S.error_invalid_parameters
    "invalid_workflow", "workflow_not_found" -> S.workflow_unavailable; "storage" -> S.error_storage
    "busy", "capacity", "rate_limited" -> S.error_busy; "configuration_required", "client_setup_failed" -> S.error_config
    "not_found" -> S.error_not_found; "media", "output_unavailable" -> S.error_media; "too_large" -> S.error_too_large
    "history_failed" -> S.error_history; "conflict" -> S.error_conflict; "unknown" -> S.uncertain_hint
    "kaggle_launch_failed" -> S.error_kaggle_launch
    "kaggle_quota_exhausted" -> S.error_kaggle_quota
    "kaggle_wait_timeout" -> S.error_kaggle_wait
    "kaggle_lease_expired" -> S.error_kaggle_lease
    "kaggle_worker_lost" -> S.error_kaggle_worker_lost
    "kaggle_execution_failed" -> S.error_kaggle_execution
    "kaggle_unavailable" -> S.error_kaggle_unavailable
    else -> S.error_generic
}
@Composable internal fun comfyError(code: String) = tr(comfyErrorResource(code))
@Composable internal fun fieldLabel(input: ComfyInput): String {
    val id = when (input.key) {
        "prompt", "text" -> S.field_prompt; "negative_prompt" -> S.field_negative_prompt; "seed" -> S.field_seed; "steps" -> S.field_steps
        "width" -> S.field_width; "height" -> S.field_height; "duration", "duration_seconds" -> S.field_duration
        "size" -> S.size; "resolution" -> S.field_resolution; "aspect_ratio" -> S.field_aspect_ratio
        "cfg", "guidance", "guidance_scale", "prompt_influence" -> S.field_guidance
        "model", "model_name", "checkpoint", "ckpt_name", "diffusion_model", "unet_name" -> S.field_model
        "lora", "lora_name" -> S.field_lora; "strength_model", "strength_clip", "lora_strength" -> S.field_strength
        "clip_name", "clip_name1", "clip_name2", "text_encoder", "text_encoder_2" -> S.field_encoder
        "vae", "vae_name" -> S.field_vae; "image" -> S.field_image; "lyrics" -> S.field_lyrics
        "output_format" -> S.field_format; "quality" -> S.field_quality; "style" -> S.field_style; "language", "language_code" -> S.field_language
        "voice", "voice_id" -> S.field_voice; "camera_fixed" -> S.field_camera_fixed; "loop" -> S.field_loop
        "generate_audio" -> S.field_generate_audio; "raw" -> S.field_raw
        "image_weight" -> S.field_image_weight
        "creativity" -> S.field_creativity
        "background" -> S.field_background
        "color_preservation" -> S.field_color_preservation
        "face_enhancement" -> S.field_face_enhancement
        "loudness_rate" -> S.field_loudness_rate
        "pitch_rate" -> S.field_pitch_rate
        "speech_rate" -> S.field_speech_rate
        "stability" -> S.field_stability
        "movement_amplitude" -> S.field_movement_amplitude
        "prompt_extend" -> S.field_prompt_extend
        "rendering_speed" -> S.field_rendering_speed
        "sample_rate" -> S.field_sample_rate
        "subject_detection" -> S.field_subject_detection
        "thinking" -> S.field_thinking
        else -> null
    }
    return id?.let { tr(it) } ?: input.title
}

/** Transport text remains data; local status/error copy follows the selected app locale. */
@Composable internal fun localMessage(message: String): String {
    val resource = when (message) {
        "passkey_none" -> S.passkey_none
        "passkey_cancelled" -> S.passkey_cancelled
        "passkey_unsupported" -> S.passkey_unsupported
        "passkey_provider" -> S.passkey_provider
        "passkey_invalid" -> S.passkey_invalid
        "This turn was interrupted." -> S.chat_interrupted
        "This turn was error." -> S.chat_failed
        "This message has attachments. Open the web client to view them." -> S.chat_attachments
        "Connection interrupted. Reconnect to check server history; no work is automatically resent." -> S.chat_connection_lost
        "Unable to complete this request. Check Settings and reload." -> S.chat_request_failed
        "Stream verification failed. Reload saved history." -> S.chat_stream_error
        "This server and app use incompatible protocol versions. Update the app or check the server URL." -> S.chat_protocol_error
        "The server returned an unreadable response." -> S.chat_invalid_response
        "Unable to confirm session adoption. Refresh and try Continue here again." -> S.chat_adoption_unknown

        "Your session has ended. Sign in again in Settings." -> S.error_auth
        "Your session has expired. Sign in again." -> S.auth_expired
        "Saved sign-in could not be unlocked. Sign in again." -> S.auth_unlocked
        "Sign-in could not be saved securely. Sign in again." -> S.auth_save
        "Could not open a browser. Install or enable a browser and try again." -> S.auth_browser
        "Signed out on this phone. Remote revocation is pending." -> S.auth_pending_revoke
        "Signed out on this phone. Remote revocation could not be confirmed; use Account security to revoke this device." -> S.auth_revocation_failed
        "Signed out." -> S.auth_signed_out
        "Sign-in callback was not accepted. Start sign-in again." -> S.auth_callback
        "Sign-in was cancelled. You can try again." -> S.auth_cancelled
        "Too many sign-in attempts. Wait a few minutes and try again." -> S.auth_rate_limited
        "Native sign-in is not available on this server yet." -> S.auth_unavailable
        "Could not complete sign-in. Start again.", "Could not complete sign-in. Start again when connected.",
        "The server could not complete sign-in. Start sign-in again.", "Sign-in expired or could not be verified. Start sign-in again.",
        "Sign-in failed. Start sign-in again.", "Invalid sign-in response.", "The sign-in response was invalid. Start sign-in again." -> S.auth_failed
        "Settings could not be saved. Try again.", "Unable to save settings." -> S.settings_failed
        "Enter a valid server URL." -> S.url_invalid
        "The URL cannot contain credentials, a query, or a fragment." -> S.url_credentials
        "Enter the server origin without a path, for example https://agent.karewinkcloud.com." -> S.url_origin
        "Use HTTPS. Debug builds also allow HTTP on localhost or 127.0.0.1." -> S.url_https
        else -> null
    }
    return resource?.let { tr(it) } ?: message
}
