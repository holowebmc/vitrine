package dev.holoweb.vitrine.impl;
//? if >=26.2 {

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.holoweb.vitrine.impl.mixin.VulkanCommandEncoderAccessor;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExternalImageFormatProperties;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatProperties2;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImportMemoryWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkMemoryWin32HandlePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalImageFormatInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

/**
 * Zero-copy frames on Minecraft's Vulkan backend. Native keeps a ring of 3 NT-shared D3D11 textures per
 * page; here each one is imported once as a VkImage, and every new frame is copied GPU to GPU into the
 * page's Minecraft texture inside Minecraft's own command stream. A slot goes back to CEF only when the
 * fence of the submission that read it has signalled.
 */
final class VulkanFrames {
	private static final int D3D11_TEXTURE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT;

	private static Boolean available;
	private static VulkanCommandEncoder encoder;
	private static VkDevice device;
	private static int queueFamily;

	private record InFlight(GpuFence fence, int slot, int generation) {}

	private final long[] frame = new long[5];
	private final long[] images = new long[3], memories = new long[3];
	private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
	private int generation = -1;

	/** Once, on the render thread: is the whole chain (extension, import support, same adapter) there? */
	static boolean available(VulkanCommandEncoder enc) {
		if (available == null) {
			available = init(enc);
			if (!available) VitrineClient.LOG.info("Vitrine: Vulkan zero-copy unavailable, using CPU frames");
		}
		return available;
	}

	private static boolean init(VulkanCommandEncoder enc) {
		VulkanDevice vd = ((VulkanCommandEncoderAccessor) enc).vitrine$device();
		VkDevice dev = vd.vkDevice();
		if (!dev.getCapabilities().VK_KHR_external_memory_win32) return false;
		try (MemoryStack st = MemoryStack.stackPush()) {
			// Can this GPU import D3D11 textures as sampled/copied RGBA8 images?
			var external = VkExternalImageFormatProperties.calloc(st).sType$Default();
			var props = VkImageFormatProperties2.calloc(st).sType$Default().pNext(external);
			var info = VkPhysicalDeviceImageFormatInfo2.calloc(st).sType$Default()
					.pNext(VkPhysicalDeviceExternalImageFormatInfo.calloc(st).sType$Default().handleType(D3D11_TEXTURE))
					.format(VK_FORMAT_R8G8B8A8_UNORM).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL)
					.usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
			if (vkGetPhysicalDeviceImageFormatProperties2(dev.getPhysicalDevice(), info, props) != VK_SUCCESS
					|| (external.externalMemoryProperties().externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0) {
				return false;
			}
			// Create the D3D11 device on the same GPU (by LUID).
			var id = VkPhysicalDeviceIDProperties.calloc(st).sType$Default();
			vkGetPhysicalDeviceProperties2(dev.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(st).sType$Default().pNext(id));
			if (!id.deviceLUIDValid() || !Native.gpuInitVulkan(id.deviceLUID().getLong(0))) return false;
		}
		encoder = enc;
		device = dev;
		queueFamily = vd.graphicsQueue().queueFamilyIndex();
		VitrineClient.LOG.info("Vitrine: frames via GPU (D3D11 -> Vulkan external memory)");
		return true;
	}

	/** Render thread, outside render passes. Copies the newest frame into {@code dst}; false if none. */
	boolean update(long handle, VulkanGpuTexture dst, int width, int height) {
		retireFinished(handle);
		if (!Native.vkTake(handle, frame)) return false;
		int slot = (int) frame[0], gen = (int) frame[2];
		if (frame[3] != width || frame[4] != height) { // mid-resize
			Native.vkRelease(handle, slot, gen);
			return false;
		}
		if (gen != generation) { // the ring was recreated (resize): drop the old imports
			destroyImports(handle);
			generation = gen;
		}
		if (images[slot] == 0) importSlot(slot, frame[1], width, height);

		try (MemoryStack st = MemoryStack.stackPush()) {
			VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
			// D3D11 wrote it (and waited for the GPU) before handing it over: acquire from the external owner.
			ownership(cb, st, images[slot], VK_QUEUE_FAMILY_EXTERNAL, queueFamily, 0, VK_ACCESS_TRANSFER_READ_BIT);
			VulkanCommandEncoder.memoryBarrier(cb, st); // dst: after whatever Minecraft did with it before
			var region = VkImageCopy.calloc(1, st);
			region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
			region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
			region.extent().set(width, height, 1);
			// Minecraft's Vulkan backend keeps every image in GENERAL layout.
			vkCmdCopyImage(cb, images[slot], VK_IMAGE_LAYOUT_GENERAL, dst.vkImage(), VK_IMAGE_LAYOUT_GENERAL, region);
			ownership(cb, st, images[slot], queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_ACCESS_TRANSFER_READ_BIT, 0);
			VulkanCommandEncoder.memoryBarrier(cb, st); // dst: before Minecraft samples it
			vkEndCommandBuffer(cb);
			encoder.execute(cb);
		}
		inFlight.add(new InFlight(encoder.createFence(), slot, gen));
		return true;
	}

	private static void ownership(VkCommandBuffer cb, MemoryStack st, long image, int from, int to, int srcAccess, int dstAccess) {
		var barrier = VkImageMemoryBarrier.calloc(1, st).sType$Default()
				.srcAccessMask(srcAccess).dstAccessMask(dstAccess)
				.oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(from).dstQueueFamilyIndex(to)
				.image(image);
		barrier.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
		vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
	}

	private void importSlot(int slot, long ntHandle, int width, int height) {
		try (MemoryStack st = MemoryStack.stackPush()) {
			var ci = VkImageCreateInfo.calloc(st).sType$Default()
					.pNext(VkExternalMemoryImageCreateInfo.calloc(st).sType$Default().handleTypes(D3D11_TEXTURE))
					.imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
					.mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
					.tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			ci.extent().set(width, height, 1);
			LongBuffer p = st.mallocLong(1);
			check(vkCreateImage(device, ci, null, p), "vkCreateImage");
			long image = p.get(0);

			var handleProps = VkMemoryWin32HandlePropertiesKHR.calloc(st).sType$Default();
			check(KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR(device, D3D11_TEXTURE, ntHandle, handleProps),
					"vkGetMemoryWin32HandlePropertiesKHR");
			var req = VkMemoryRequirements.calloc(st);
			vkGetImageMemoryRequirements(device, image, req);
			int types = req.memoryTypeBits() & handleProps.memoryTypeBits();
			if (types == 0) throw new IllegalStateException("no memory type can import the D3D11 texture");

			// Importing an NT handle does not take ownership; native closes it when the ring goes away.
			var ai = VkMemoryAllocateInfo.calloc(st).sType$Default()
					.pNext(VkImportMemoryWin32HandleInfoKHR.calloc(st).sType$Default()
							.pNext(VkMemoryDedicatedAllocateInfo.calloc(st).sType$Default().image(image).address())
							.handleType(D3D11_TEXTURE).handle(ntHandle))
					.allocationSize(req.size()).memoryTypeIndex(Integer.numberOfTrailingZeros(types));
			check(vkAllocateMemory(device, ai, null, p), "vkAllocateMemory (import)");
			long memory = p.get(0);
			check(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");
			images[slot] = image;
			memories[slot] = memory;
		}
	}

	/** Gives back the slots whose copy has finished on the GPU. */
	private void retireFinished(long handle) {
		while (!inFlight.isEmpty() && inFlight.peek().fence.awaitCompletion(0)) {
			InFlight f = inFlight.poll();
			f.fence.close();
			Native.vkRelease(handle, f.slot, f.generation);
		}
	}

	private void destroyImports(long handle) {
		for (InFlight f : inFlight) { // rare (resize/close): just wait for the copies to finish
			f.fence.awaitCompletion(GpuFence.NO_TIMEOUT);
			f.fence.close();
			Native.vkRelease(handle, f.slot, f.generation);
		}
		inFlight.clear();
		for (int i = 0; i < 3; i++) {
			if (images[i] != 0) vkDestroyImage(device, images[i], null);
			if (memories[i] != 0) vkFreeMemory(device, memories[i], null);
			images[i] = memories[i] = 0;
		}
	}

	void close(long handle) {
		destroyImports(handle);
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException("Vitrine: " + what + " failed (" + result + ")");
	}
}
//?}
