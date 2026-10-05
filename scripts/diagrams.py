#!/usr/bin/env python3
"""Generates architecture diagram, RBD, FTA and results chart as PNG files."""
import json
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "..", "docs")
os.makedirs(OUT, exist_ok=True)

BOX = dict(boxstyle="round,pad=0.35", facecolor="#e8f0fe", edgecolor="#1a56b0", linewidth=1.4)
BOX_DB = dict(boxstyle="round,pad=0.35", facecolor="#fde8e8", edgecolor="#b03030", linewidth=1.4)
BOX_FT = dict(boxstyle="round,pad=0.35", facecolor="#e6f6e6", edgecolor="#1d7a2e", linewidth=1.4)
ARROW = dict(arrowstyle="->", color="#333", lw=1.3)


def architecture():
    fig, ax = plt.subplots(figsize=(11, 7.5))
    ax.set_xlim(0, 11)
    ax.set_ylim(0, 8)
    ax.axis("off")

    def box(x, y, w, h, text, style=BOX):
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=9.5, bbox=style)

    ax.text(5.5, 7.7, "Fault-Tolerant University Information System (AITU)",
            ha="center", fontsize=13, weight="bold")

    box(4.0, 6.6, 3.0, 0.75, "API Gateway :8080\nLB | timeouts | retry | circuit breaker", BOX_FT)
    ax.text(7.15, 6.95, "x1", fontsize=9)

    services = [
        (0.3, "Student\nService :8081\nx2 replicas"),
        (2.9, "Payment\nService :8082\nx2 replicas"),
        (5.5, "Records\nService :8083\nx2 replicas"),
        (8.1, "Timetable\nService :8084\nx2 replicas"),
    ]
    for x, t in services:
        box(x, 4.6, 2.2, 1.0, t, BOX_FT)
        ax.annotate("", xy=(x + 1.1, 5.6), xytext=(5.5, 6.6), arrowprops=ARROW)

    box(2.4, 2.6, 2.6, 0.8, "Chaos endpoints\n/chaos: delay | error | crash", BOX)
    for x, _ in services:
        ax.annotate("", xy=(x + 1.1, 4.6), xytext=(3.7, 3.4), arrowprops=dict(arrowstyle="->", color="#b06000", lw=0.9, linestyle="--"))

    box(1.0, 0.8, 3.4, 0.9, "PostgreSQL Primary\njdbc:postgresql://postgres-primary", BOX_DB)
    box(6.6, 0.8, 3.4, 0.9, "PostgreSQL Standby\nreplicated writes, failover target", BOX_DB)
    ax.annotate("", xy=(6.6, 1.25), xytext=(4.4, 1.25), arrowprops=dict(arrowstyle="<->", color="#1a56b0", lw=1.2))
    ax.text(5.5, 1.45, "replication", ha="center", fontsize=8.5, color="#1a56b0")
    ax.annotate("", xy=(1.8, 1.7), xytext=(2.9, 4.6), arrowprops=ARROW)
    ax.annotate("", xy=(8.6, 1.7), xytext=(7.5, 4.6), arrowprops=ARROW)

    ax.text(5.5, 0.25, "Clients -> Gateway -> Services -> replicated PostgreSQL | health checks + restart policy",
            ha="center", fontsize=9, color="#444")
    fig.savefig(os.path.join(OUT, "architecture.png"), dpi=160, bbox_inches="tight")
    plt.close(fig)
    print("architecture.png")


def rbd():
    fig, ax = plt.subplots(figsize=(10, 4.6))
    ax.set_xlim(0, 12)
    ax.set_ylim(0, 5)
    ax.axis("off")

    def box(x, y, w, h, text, style=BOX):
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=9, bbox=style)

    ax.text(6, 4.7, "Reliability Block Diagram (fault-tolerant configuration)",
            ha="center", fontsize=12, weight="bold")

    box(0.2, 2.0, 1.7, 0.9, "Gateway\nR=0.999")
    box(2.6, 3.1, 2.0, 0.7, "Student-1\nR=0.955")
    box(2.6, 1.6, 2.0, 0.7, "Student-2\nR=0.955")
    box(5.2, 3.1, 2.0, 0.7, "Records-1\nR=0.955")
    box(5.2, 1.6, 2.0, 0.7, "Records-2\nR=0.955")
    box(7.8, 3.1, 2.0, 0.7, "DB primary\nR=0.930")
    box(7.8, 1.6, 2.0, 0.7, "DB standby\nR=0.930")
    box(10.2, 2.0, 1.6, 0.9, "Network\nR=0.997")

    ax.annotate("", xy=(2.6, 3.45), xytext=(1.9, 2.45), arrowprops=ARROW)
    ax.annotate("", xy=(2.6, 1.95), xytext=(1.9, 2.45), arrowprops=ARROW)
    ax.annotate("", xy=(5.2, 3.45), xytext=(4.6, 3.45), arrowprops=ARROW)
    ax.annotate("", xy=(5.2, 1.95), xytext=(4.6, 1.95), arrowprops=ARROW)
    ax.annotate("", xy=(7.8, 3.45), xytext=(7.2, 3.45), arrowprops=ARROW)
    ax.annotate("", xy=(7.8, 1.95), xytext=(7.2, 1.95), arrowprops=ARROW)
    ax.annotate("", xy=(10.2, 2.45), xytext=(9.8, 3.45), arrowprops=ARROW)
    ax.annotate("", xy=(10.2, 2.45), xytext=(9.8, 1.95), arrowprops=ARROW)

    ax.text(6, 0.7, "R_system = R_gateway x [1-(1-R_app)^2] x [1-(1-R_db)^2] x R_net = 0.9891",
            ha="center", fontsize=10, family="monospace",
            bbox=dict(boxstyle="round,pad=0.3", facecolor="#fffbe6", edgecolor="#b08d00"))
    fig.savefig(os.path.join(OUT, "rbd.png"), dpi=160, bbox_inches="tight")
    plt.close(fig)
    print("rbd.png")


def fta():
    fig, ax = plt.subplots(figsize=(11, 6.2))
    ax.set_xlim(0, 12)
    ax.set_ylim(0, 7)
    ax.axis("off")

    def box(x, y, w, h, text, style=BOX):
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=8.5, bbox=style)

    def gate(x, y, label, color="#b03030"):
        c = plt.Circle((x, y), 0.28, facecolor="white", edgecolor=color, lw=1.6)
        ax.add_patch(c)
        ax.text(x, y, label, ha="center", va="center", fontsize=8, weight="bold", color=color)

    ax.text(6, 6.7, "Fault Tree: student service unavailable", ha="center", fontsize=12, weight="bold")
    box(4.4, 5.7, 3.2, 0.7, "TOP: service unavailable", BOX_DB)
    gate(6.0, 4.9, "OR")

    for x, t in [(1.4, "Gateway\nfailure"), (10.6, "Network\nfailure")]:
        box(x - 0.8, 3.4, 1.6, 0.7, t, BOX_DB)
        ax.annotate("", xy=(6.0, 4.62), xytext=(x, 4.1), arrowprops=ARROW)

    box(3.4, 3.4, 2.4, 0.7, "App subsystem down", BOX_DB)
    gate(4.6, 2.6, "AND", "#b08d00")
    ax.annotate("", xy=(4.6, 4.62), xytext=(4.6, 4.1), arrowprops=ARROW)

    box(6.2, 3.4, 2.4, 0.7, "DB subsystem down", BOX_DB)
    gate(7.4, 2.6, "AND", "#b08d00")
    ax.annotate("", xy=(7.4, 4.62), xytext=(7.4, 4.1), arrowprops=ARROW)

    for x, t in [(3.4, "Student-1\nfail"), (5.6, "Student-2\nfail")]:
        box(x - 0.7, 1.2, 1.4, 0.7, t)
        ax.annotate("", xy=(4.6, 2.32), xytext=(x, 1.9), arrowprops=ARROW)

    for x, t in [(6.4, "DB primary\nfail"), (8.6, "DB standby\nfail")]:
        box(x - 0.7, 1.2, 1.4, 0.7, t)
        ax.annotate("", xy=(7.4, 2.32), xytext=(x, 1.9), arrowprops=ARROW)

    ax.text(6, 0.4, "With 2 replicas per service and 2 DB nodes, the AND gates require both members to fail.\n"
                    "Single points of failure are the gateway and the Docker network.",
            ha="center", fontsize=9, color="#444")
    fig.savefig(os.path.join(OUT, "fta.png"), dpi=160, bbox_inches="tight")
    plt.close(fig)
    print("fta.png")


def results_chart():
    with open(os.path.join(BASE, "..", "experiments", "analysis.json")) as f:
        data = json.load(f)
    names = [r["experiment"].replace("_", " ") for r in data["comparison"]]
    base = [r["baseline_success_rate"] for r in data["comparison"]]
    ft = [r["ft_success_rate"] for r in data["comparison"]]

    fig, ax = plt.subplots(figsize=(10.5, 5.2))
    x = range(len(names))
    w = 0.38
    b1 = ax.bar([i - w / 2 for i in x], base, w, label="baseline", color="#d9534f")
    b2 = ax.bar([i + w / 2 for i in x], ft, w, label="fault-tolerant", color="#5cb85c")
    for rect in list(b1) + list(b2):
        h = rect.get_height()
        ax.text(rect.get_x() + rect.get_width() / 2, h + 1, f"{h:.1f}", ha="center", fontsize=8)
    ax.set_xticks(list(x))
    ax.set_xticklabels(names, fontsize=9)
    ax.set_ylim(0, 112)
    ax.set_ylabel("successful requests, %")
    ax.set_title("Baseline vs fault-tolerant: request success rate per failure scenario")
    ax.legend()
    ax.grid(axis="y", alpha=0.3)
    fig.savefig(os.path.join(OUT, "results.png"), dpi=160, bbox_inches="tight")
    plt.close(fig)
    print("results.png")


if __name__ == "__main__":
    architecture()
    rbd()
    fta()
    results_chart()
