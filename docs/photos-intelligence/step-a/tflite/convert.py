"""Convert the validated fp32 MobileCLIP-S0 vision encoder (ONNX) to TFLite and quantise its weights.

Usage: python -I convert.py <vision_model.onnx> <out_dir>

Writes to <out_dir>:
  tf/                               onnx2tf output (SavedModel + float32/float16 .tflite), input NHWC [1,256,256,3]
  mobileclip_s0_image_fp32.tflite   float reference (copy of onnx2tf's float32 file)
  mobileclip_s0_image_fp16.tflite   onnx2tf's float16-weight file (for comparison)
  mobileclip_s0_image_drq.tflite    dynamic-range quantisation (ai-edge-quantizer dynamic_wi8_afp32):
                                    int8 weights, hybrid kernels quantise activations on the fly
  mobileclip_s0_image_w8all.tflite  weight-only int8 on every weight tensor (ai-edge-quantizer, explicit DEQUANTIZE, fp32 maths)
  mobileclip_s0_image_w8.tflite     weight-only int8 on CONV_2D/FULLY_CONNECTED weights with >= 16k elements only
                                    (the scope ../requantize.py validated in ONNX); this is the shipped model
The ONNX file is only read.
"""
import sys, os, shutil, json, traceback
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
TF_DIR = os.path.join(OUT, "tf")
log = {}

# 1. static shapes: fix the batch dim to 1 and let onnxsim fold every Shape/Reshape, otherwise
#    onnx2tf's TF path sees [?,512,?,?] inside the attention blocks and cannot transpose them.
import onnx
from onnxsim import simplify
m = onnx.load(SRC)
d = m.graph.input[0].type.tensor_type.shape.dim
for i, v in enumerate([1, 3, 256, 256]):
    d[i].ClearField("dim_param"); d[i].dim_value = v
m, ok = simplify(m, overwrite_input_shapes={"pixel_values": [1, 3, 256, 256]})
assert ok
STATIC = os.path.join(OUT, "vision_static.onnx")
onnx.save(m, STATIC)
log["static_onnx_nodes"] = len(m.graph.node)

import onnx2tf
onnx2tf.convert(
    input_onnx_file_path=STATIC,
    output_folder_path=TF_DIR,
    overwrite_input_shape=["pixel_values:1,3,256,256"],
    tflite_backend="tf_converter",  # 2.x default "flatbuffer_direct" fails on this graph (Softmax axis)
    # GELU's Erf would become a Flex (select-TF) op that no stock runtime has; use onnx2tf's
    # builtin-ops polynomial erf (Abramowitz-Stegun 7.1.26, |error| < 1.5e-7) instead.
    replace_to_pseudo_operators=["Erf"],
    copy_onnx_input_output_names_to_tflite=True,
    output_signaturedefs=True,
    non_verbose=True,
)
fp32 = [f for f in os.listdir(TF_DIR) if f.endswith("_float32.tflite")][0]
shutil.copy(os.path.join(TF_DIR, fp32), os.path.join(OUT, "mobileclip_s0_image_fp32.tflite"))
log["onnx2tf_files"] = sorted(os.listdir(TF_DIR))

shutil.copy(os.path.join(TF_DIR, fp32.replace("_float32", "_float16")), os.path.join(OUT, "mobileclip_s0_image_fp16.tflite"))

# Dynamic-range quantisation. (TFLiteConverter.from_saved_model with Optimize.DEFAULT fails on
# onnx2tf's SavedModel in TF 2.21 with an MLIR legalisation error, so use ai-edge-quantizer's
# equivalent recipe on the float TFLite instead.)
try:
    from ai_edge_quantizer import quantizer, recipe
    q = quantizer.Quantizer(os.path.join(OUT, "mobileclip_s0_image_fp32.tflite"))
    q.load_quantization_recipe(recipe.dynamic_wi8_afp32())
    q.quantize().export_model(os.path.join(OUT, "mobileclip_s0_image_drq.tflite"), overwrite=True)
    log["drq"] = "ok"
except Exception as e:
    log["drq_error"] = traceback.format_exc()[-1500:]

def weight_only(ops, min_elems):
    cfg = {"weight_tensor_config": {"num_bits": 8, "symmetric": True, "granularity": "CHANNELWISE", "dtype": "INT"},
           "compute_precision": "FLOAT", "explicit_dequantize": True, "skip_checks": False, "min_weight_elements": min_elems}
    return [{"regex": ".*", "operation": op, "algorithm_key": "min_max_uniform_quantize", "op_config": cfg} for op in ops]


# Weight-only INT8, two scopes (mirrors ../requantize.py, where only the second kept cos >= 0.98):
#   w8all: every supported op's weights (incl. depthwise and small tensors)
#   w8:    only CONV_2D / FULLY_CONNECTED weights with >= 16384 elements; depthwise and small stay fp32
for name, rcp in (("w8all", weight_only(["*"], 0)), ("w8", weight_only(["CONV_2D", "FULLY_CONNECTED"], 16384))):
    try:
        from ai_edge_quantizer import quantizer
        q = quantizer.Quantizer(os.path.join(OUT, "mobileclip_s0_image_fp32.tflite"))
        q.load_quantization_recipe(rcp)
        q.quantize().export_model(os.path.join(OUT, f"mobileclip_s0_image_{name}.tflite"), overwrite=True)
        log[name] = "ok"
    except Exception as e:
        log[name + "_error"] = traceback.format_exc()[-1500:]

json.dump(log, open(os.path.join(OUT, "convert_log.json"), "w"), indent=1)
print(json.dumps(log, indent=1))
