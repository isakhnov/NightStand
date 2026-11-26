"""Step 1: print input/output names, shapes and types of the MobileCLIP-S0 ONNX models.
Usage: python -I inspect_models.py <model_dir>"""
import sys, os
import onnxruntime as ort

d = sys.argv[1]
for name in ["vision_model_quantized.onnx", "vision_model.onnx", "text_model.onnx"]:
    p = os.path.join(d, "onnx", name)
    s = ort.InferenceSession(p, providers=["CPUExecutionProvider"])
    print(f"== {name}  ({os.path.getsize(p)/1e6:.1f} MB)")
    for i in s.get_inputs():
        print(f"   in : {i.name:16s} {i.shape} {i.type}")
    for o in s.get_outputs():
        print(f"   out: {o.name:16s} {o.shape} {o.type}")
    m = s.get_modelmeta()
    print(f"   opset/producer: {m.producer_name} {m.version}")
