# scanner.py
import subprocess

def scan_image(image_name: str, output_file: str = "trivy-report.json", scanners: str = "vuln,secret,misconfig") -> None:
    command = [
        "trivy", "image",
        "--format", "json",
        "--scanners", scanners,
        "--timeout", "15m",
        "-o", output_file,
        image_name
    ]
    try:
        print(f"[INFO] Running image security analysis on image: {image_name}")
        subprocess.run(command, check=True)
        print(f"[INFO] Scan complete. Report saved to {output_file}")
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Image security analysis failed: {e}")
        raise
    except FileNotFoundError:
        print("[ERROR] Image security scanner is not installed or not found in PATH.")
        raise
