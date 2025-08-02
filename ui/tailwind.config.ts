import type { Config } from "tailwindcss";

const config: Config = {
	darkMode: ["class"],
	content: [
		"./pages/**/*.{ts,tsx}",
		"./components/**/*.{ts,tsx}",
		"./app/**/*.{ts,tsx}",
		"./src/**/*.{ts,tsx}",
	],
	prefix: "",
	theme: {
		container: {
			center: true,
			padding: '2rem',
			screens: {
				'2xl': '1400px'
			}
		},
		extend: {
			fontFamily: {
				sans: ['"Geist Sans"', 'Inter', 'sans-serif'],
				mono: ['"Geist Mono"', 'Menlo', 'Monaco', 'monospace'],
			},
			fontSize: {
				'btn-lg': '16px',  /* Corresponds to text-base */
				'btn-md': '14px',  /* Corresponds to text-sm */
				'btn-sm': '12px',  /* Corresponds to text-xs */
			},
			colors: {
				border: "hsl(var(--border))",
				input: "hsl(var(--input))",
				ring: "hsl(var(--ring))",
				background: "hsl(var(--background))",
				foreground: "hsl(var(--foreground))",
				
				// Geist UI Color System - mapped to existing Vercel colors
				background1: "hsl(var(--background))",
				background2: "hsl(var(--muted))",
				
				// Accent Gray Scale (Geist UI) - mapped to existing gray scale
				accentGray: {
					1: "hsl(var(--gray-50))",   
					2: "hsl(var(--gray-100))",  
					3: "hsl(var(--gray-200))",  
					4: "hsl(var(--gray-300))",  
					5: "hsl(var(--gray-400))",  
					6: "hsl(var(--gray-500))",  
					7: "hsl(var(--gray-600))",  
					8: "hsl(var(--gray-700))",  
				},
				
				// New Masaic Brand Colors
				'warm-whisper-grey': "hsl(var(--warm-whisper-grey))",
				'soft-neutral-white': "hsl(var(--soft-neutral-white))",
				'cool-silver-grey': "hsl(var(--cool-silver-grey))",
				'pure-white': "hsl(var(--pure-white))",
				
				// Semantic Colors (Updated)
				primary: {
					DEFAULT: "hsl(var(--primary))",
					foreground: "hsl(var(--primary-foreground))",
					light: "hsl(var(--gray-400))",
					dark: "hsl(var(--gray-600))",
				},
				'positive-trend': {
					DEFAULT: "#2e7d32",
					foreground: "#ffffff",
				},
				'negative-trend': {
					DEFAULT: "#ef6c00",
					foreground: "#ffffff",
				},
				'critical-alert': {
					DEFAULT: "#c62828",
					foreground: "#ffffff",
				},
				'opportunity': {
					DEFAULT: "#c49b0b",
					foreground: "#ffffff",
				},
				success: {
					DEFAULT: "#22c55e",
					light: "#16a34a",
					dark: "#15803d",
				},
				warning: {
					DEFAULT: "#eab308",
					light: "#ca8a04",
					dark: "#a16207",
				},
				error: {
					DEFAULT: "hsl(var(--destructive))",
					light: "#dc2626",
					dark: "#b91c1c",
				},
				
				// Original Vercel-like grayscale palette
				gray: {
					50: "hsl(var(--gray-50))",   // #fafafa
					100: "hsl(var(--gray-100))", // #f5f5f5
					200: "hsl(var(--gray-200))", // #e5e5e5
					300: "hsl(var(--gray-300))", // #d4d4d4
					400: "hsl(var(--gray-400))", // #a3a3a3
					500: "hsl(var(--gray-500))", // #737373
					600: "hsl(var(--gray-600))", // #525252
					700: "hsl(var(--gray-700))", // #404040
					800: "hsl(var(--gray-800))", // #262626
					900: "hsl(var(--gray-900))", // #171717
				},
				secondary: {
					DEFAULT: "hsl(var(--secondary))",
					foreground: "hsl(var(--secondary-foreground))",
				},
				destructive: {
					DEFAULT: "hsl(var(--destructive))",
					foreground: "hsl(var(--destructive-foreground))",
				},
				muted: {
					DEFAULT: "hsl(var(--muted))",
					foreground: "hsl(var(--muted-foreground))",
				},
				accent: {
					DEFAULT: "hsl(var(--accent))",
					foreground: "hsl(var(--accent-foreground))",
				},
				popover: {
					DEFAULT: "hsl(var(--popover))",
					foreground: "hsl(var(--popover-foreground))",
				},
				card: {
					DEFAULT: "hsl(var(--card))",
					foreground: "hsl(var(--card-foreground))",
				},
				// Minimal accent colors for when needed
				blue: {
					50: "#eff6ff",
					500: "#3b82f6",
					600: "#2563eb",
					700: "#1d4ed8",
				},
				red: {
					50: "#fef2f2",
					500: "#ef4444",
					600: "#dc2626",
					700: "#b91c1c",
				},
				green: {
					50: "#f0fdf4",
					500: "#22c55e",
					600: "#16a34a",
					700: "#15803d",
				},
				yellow: {
					50: "#fefce8",
					500: "#eab308",
					600: "#ca8a04",
					700: "#a16207",
				},
			},
			borderRadius: {
				none: '0',
				sm: '4px',
				DEFAULT: '6px',  /* `rounded` = 6px (Geist standard) */
				md: '8px',
				lg: '12px',       /* For modals, large cards */
				xl: '16px',       /* For very large containers */
			},
			boxShadow: {
				xs: '0 1px 2px rgba(0,0,0,0.05)',    /* Subtle border-like shadow */
				sm: '0 2px 4px rgba(0,0,0,0.10)',    /* Tooltip */
				md: '0 4px 8px rgba(0,0,0,0.10)',    /* Card */
				lg: '0 8px 16px rgba(0,0,0,0.12)',   /* Raised card / popover */
				xl: '0 12px 24px rgba(0,0,0,0.15)',  /* Deep modal shadow */
				'tile-glow-semantic': '0 0 20px var(--tile-glow-color-semantic)',
			},
			keyframes: {
				'accordion-down': {
					from: {
						height: '0'
					},
					to: {
						height: 'var(--radix-accordion-content-height)'
					}
				},
				'accordion-up': {
					from: {
						height: 'var(--radix-accordion-content-height)'
					},
					to: {
						height: '0'
					}
				},
				'pulse-faint': {
					'0%, 100%': {
						opacity: '1'
					},
					'50%': {
						opacity: '0.6'
					}
				}
			},
			animation: {
				'accordion-down': 'accordion-down 0.2s ease-out',
				'accordion-up': 'accordion-up 0.2s ease-out',
				'pulse-faint': 'pulse-faint 2s cubic-bezier(0.4, 0, 0.6, 1) infinite'
			}
		}
	},
	plugins: [
		require("tailwindcss-animate"),
		require("@tailwindcss/forms"),
		require("@tailwindcss/typography"),
	],
} satisfies Config;

export default config;
