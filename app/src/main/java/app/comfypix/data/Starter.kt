package app.comfypix.data

/** The classic text-to-image graph, in API format. Shipped so the app works on first launch. */
const val STARTER_WORKFLOW = """{
 "3": {"class_type": "KSampler", "inputs": {"seed": 156680208700286, "steps": 20, "cfg": 8, "sampler_name": "euler", "scheduler": "normal", "denoise": 1, "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}, "_meta": {"title": "KSampler"}},
 "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "v1-5-pruned-emaonly.safetensors"}, "_meta": {"title": "Load Checkpoint"}},
 "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 512, "height": 512, "batch_size": 1}, "_meta": {"title": "Empty Latent Image"}},
 "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "beautiful scenery nature glass bottle landscape, purple galaxy bottle", "clip": ["4", 1]}, "_meta": {"title": "CLIP Text Encode (Prompt)"}},
 "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "text, watermark", "clip": ["4", 1]}, "_meta": {"title": "CLIP Text Encode (Prompt)"}},
 "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}, "_meta": {"title": "VAE Decode"}},
 "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ComfyPix", "images": ["8", 0]}, "_meta": {"title": "Save Image"}}
}"""
