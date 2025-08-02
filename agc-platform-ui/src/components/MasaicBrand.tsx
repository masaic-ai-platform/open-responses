"use client"

import { Sparkles } from "lucide-react"
import { useState } from "react"

export default function MasaicBrand() {
  const [isHovered, setIsHovered] = useState(false)
  const [isBadgeHovered, setIsBadgeHovered] = useState(false)

  return (
    <div className="flex items-center gap-3 p-4">
      {/* Logo/Icon */}
      <div className="relative">
        <div className="w-12 h-12 flex items-center justify-center">
          <img 
            src="/Masaic-M-Logo.png" 
            alt="Masaic Logo" 
            className="w-7.5 h-7.5"
          />
        </div>
      </div>

      {/* Brand Text */}
      <div className="flex flex-col">
        <div className="flex items-center gap-2">
          <h1
            className="text-xl font-bold text-white"
            style={{
              fontFamily: '"Geist Sans", Inter, sans-serif',
            }}
          >
            <span className="tracking-normal">Masaic</span> <span className="tracking-wide">AgC</span>
          </h1>
          <div className="relative">
            {/* Animated Ring */}
            <div className="absolute inset-0 rounded-full animate-badge-ring opacity-60"></div>
            <div className="absolute inset-0 rounded-full animate-badge-ring-delayed opacity-40"></div>

            <span
              className={`relative inline-flex items-center px-2.5 py-1 rounded-full text-xs font-semibold bg-positive-trend/10 text-positive-trend border border-positive-trend/20 animate-fade-in transition-all duration-200 cursor-help ${
                isBadgeHovered ? "bg-positive-trend/15 shadow-md" : ""
              }`}
              style={{
                fontFamily: '"Geist Sans", Inter, sans-serif',
                boxShadow: isBadgeHovered
                  ? "inset 0 1px 3px rgba(46, 125, 50, 0.15), 0 0 12px rgba(46, 125, 50, 0.25)"
                  : "inset 0 1px 2px rgba(46, 125, 50, 0.08)",
                transform: isBadgeHovered ? "scale(1.05)" : "scale(1)",
              }}
              onMouseEnter={() => setIsBadgeHovered(true)}
              onMouseLeave={() => setIsBadgeHovered(false)}
            >
              1
            </span>

            {/* Badge Tooltip */}
            {isBadgeHovered && (
              <div className="absolute top-full left-1/2 transform -translate-x-1/2 mt-2 px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg shadow-xl z-50 whitespace-nowrap animate-fade-in">
                <div
                  className="text-xs text-gray-300 font-medium"
                  style={{
                    fontFamily: '"Geist Sans", Inter, sans-serif',
                  }}
                >
                  Agentic Compute v1
                </div>
                <div className="absolute -top-1 left-1/2 transform -translate-x-1/2 w-2 h-2 bg-gray-800 border-l border-t border-gray-700 rotate-45"></div>
              </div>
            )}
          </div>
        </div>

        <div className="relative">
          <p
            className="text-sm font-medium"
            style={{
              fontFamily: '"Geist Sans", Inter, sans-serif',
            }}
          >
            <span className="text-gray-300">The</span>{" "}
            <span
              className="relative cursor-help"
              onMouseEnter={() => setIsHovered(true)}
              onMouseLeave={() => setIsHovered(false)}
            >
              <span className="relative inline-block">
                <span className="bg-gradient-to-r from-positive-trend to-success bg-clip-text text-transparent font-semibold">
                  agentic
                </span>
                <span className="absolute -bottom-0.5 left-0 h-[2px] bg-gradient-to-r from-positive-trend/70 to-success/50 rounded-full animate-slide-in origin-left w-full"></span>
                {isHovered && (
                  <span className="absolute -bottom-0.5 left-0 h-[2px] bg-gradient-to-r from-positive-trend to-success rounded-full animate-underline-sweep origin-left w-full"></span>
                )}
              </span>{" "}
              <span className="relative inline-block">
                <span className="bg-gradient-to-r from-success via-positive-trend to-blue-500 bg-clip-text text-transparent font-semibold">
                  computing
                </span>
                <span className="absolute -bottom-0.5 left-0 h-[2px] bg-gradient-to-r from-success/60 via-positive-trend/60 to-blue-500/60 rounded-full animate-slide-in-delayed origin-left w-full"></span>
                {isHovered && (
                  <span
                    className="absolute -bottom-0.5 left-0 h-[2px] bg-gradient-to-r from-success via-positive-trend to-blue-500 rounded-full animate-underline-sweep origin-left w-full"
                    style={{ animationDelay: "0.1s" }}
                  ></span>
                )}
              </span>
              {/* Hover Tooltip */}
              {isHovered && (
                <div className="absolute top-full left-1/2 transform -translate-x-1/2 mt-2 px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg shadow-xl z-50 whitespace-nowrap animate-fade-in">
                  <div
                    className="text-xs text-gray-200 font-medium"
                    style={{
                      fontFamily: '"Geist Sans", Inter, sans-serif',
                    }}
                  >
                    A new compute paradigm for AI agents
                  </div>
                  <div className="absolute -top-1 left-1/2 transform -translate-x-1/2 w-2 h-2 bg-gray-800 border-l border-t border-gray-700 rotate-45"></div>
                </div>
              )}
            </span>{" "}
            <span className="text-gray-300 italic font-light">platform</span>
          </p>
        </div>
      </div>
    </div>
  )
} 