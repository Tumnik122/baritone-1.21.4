import os
import shutil

src_jar = r"C:\Users\Administrator\Desktop\Gry i Mody\baritone-1.21.4\baritone-1.21.4\fabric\build\libs\baritone-fabric-1.13.1.jar"
instances_dir = r"C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances"

if not os.path.exists(src_jar):
    print(f"Error: {src_jar} does not exist!")
    exit(1)

print(f"Source jar size: {os.path.getsize(src_jar)} bytes")

count = 0
for inst in os.listdir(instances_dir):
    inst_path = os.path.join(instances_dir, inst)
    if not os.path.isdir(inst_path):
        continue
    for sub in ["minecraft", ".minecraft"]:
        mods_dir = os.path.join(inst_path, sub, "mods")
        if os.path.exists(mods_dir):
            for f in os.listdir(mods_dir):
                if "baritone" in f.lower() and f.endswith(".jar"):
                    dst = os.path.join(mods_dir, f)
                    try:
                        shutil.copy2(src_jar, dst)
                        print(f"[UPDATED] {inst}/{sub}/mods/{f}")
                        count += 1
                    except Exception as e:
                        print(f"[ERROR] {inst}/{sub}/mods/{f}: {e}")

print(f"Deployment complete. Updated {count} baritone jars.")

