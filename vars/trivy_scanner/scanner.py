# scanner.py
import subprocess

def scan_image(image_name: str, output_file: str = "trivy-report.json") -> None:
    command = [
        "trivy", "image", "--format", "json", "-o", output_file, image_name
    ]
    try:
        print(f"[INFO] Running Trivy scan on image: {image_name}")
        subprocess.run(command, check=True)
        print(f"[INFO] Scan complete. Report saved to {output_file}")
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Trivy scan failed: {e}")
        raise
    except FileNotFoundError:
        print("[ERROR] Trivy is not installed or not found in PATH.")
        raise
