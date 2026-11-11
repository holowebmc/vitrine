package dev.holoweb.vitrine.impl;
//? if <1.21.9 {
/*
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.Identifier;
//? if >=1.21.2 <1.21.6
import net.minecraft.util.TriState;
//? if >=1.21.5 <1.21.6
import net.minecraft.client.renderer.RenderPipelines;
//? if <1.21.5 {
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
//?}

// World panels before 1.21.9 (drawn through a MultiBufferSource): RenderType.text, but always with the
// page texture's linear filtering. Before 1.21.6 vanilla's text type forces nearest filtering, which
// shreds a page shown smaller than its pixel size.
// Extends RenderType only to reach its protected builders; never instantiated.
abstract class PanelRenderType extends RenderType {
	private PanelRenderType() {
		//? if >=1.21.5 {
		super("", 0, false, false, () -> {}, () -> {});
		//?} else
		super("", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
	}

	static RenderType of(Identifier texture) {
		//? if >=1.21.6 {
		return text(texture); // keeps the texture's own filter
		//?} else if >=1.21.5 {
		return create("vitrine_panel", 1536, false, false, RenderPipelines.TEXT, CompositeState.builder()
				.setTextureState(new TextureStateShard(texture, TriState.TRUE, false))
				.setLightmapState(LIGHTMAP)
				.createCompositeState(false));
		//?} else {
		return create("vitrine_panel", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 1536, false, false,
				CompositeState.builder()
						.setShaderState(RENDERTYPE_TEXT_SHADER)
						//? if >=1.21.2 {
						.setTextureState(new TextureStateShard(texture, TriState.TRUE, false))
						//?} else
						.setTextureState(new TextureStateShard(texture, true, false))
						.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
						.setLightmapState(LIGHTMAP)
						.createCompositeState(false));
		//?}
	}
}
*///?}
