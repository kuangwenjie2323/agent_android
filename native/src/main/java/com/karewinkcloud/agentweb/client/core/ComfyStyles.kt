package com.karewinkcloud.agentweb.client.core

/** A one-tap style: its phrase is appended to the prompt (English reads well for every model). */
data class ComfyStyle(val key: String, val phrase: String)

val comfyStyles = listOf(
    ComfyStyle("portrait", "photorealistic portrait, 85mm lens, soft natural light, shallow depth of field"),
    ComfyStyle("fashion", "high-fashion editorial photo, bold styling, studio lighting, magazine look"),
    ComfyStyle("anime", "anime illustration, clean line art, vibrant cel shading"),
    ComfyStyle("cartoon3d", "3D cartoon render, soft global illumination, cute characters"),
    ComfyStyle("cinematic", "cinematic film still, anamorphic lens, dramatic lighting, film grain"),
    ComfyStyle("product", "commercial product photography, clean background, softbox lighting, crisp detail"),
    ComfyStyle("poster", "graphic poster design, bold typography, strong composition"),
    ComfyStyle("watercolor", "watercolor painting, soft washes, textured paper"),
    ComfyStyle("guofeng", "traditional Chinese ink painting, guofeng style, elegant brushwork"),
    ComfyStyle("cyberpunk", "cyberpunk scene, neon lights, rain-soaked streets, futuristic"),
)

/** Varied starting points so creations are not all landscapes. */
val comfyInspirations = listOf(
    "A street food vendor in Bangkok at night, steam rising from the wok, warm lantern light",
    "A golden retriever wearing tiny sunglasses on a skateboard, summer boardwalk",
    "A minimalist perfume bottle on rippled sand, morning light, luxury advertisement",
    "An astronaut tending a vegetable garden inside a glass dome on Mars",
    "A cozy ramen shop interior during a rainy evening, steam and neon reflections",
    "A young cellist performing on a rooftop at sunset, city skyline behind",
    "A futuristic electric motorcycle in a white studio, three-quarter view",
    "A grandmother teaching her granddaughter to make dumplings in a sunlit kitchen",
    "A koi pond seen from above, lily pads and drifting cherry blossom petals",
    "A retro 1980s arcade full of glowing cabinets and kids playing",
    "A knight in ornate silver armor kneeling in a misty cathedral",
    "A colorful bowl of acai with fresh fruit on a marble table, food photography",
)

fun applyStyle(prompt: String, style: ComfyStyle): String {
    if (prompt.contains(style.phrase)) return prompt
    val base = prompt.trim().trimEnd(',', '，', '.', '。')
    return if (base.isEmpty()) style.phrase else "$base, ${style.phrase}"
}
