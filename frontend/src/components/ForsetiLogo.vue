<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  /** Variante del logo */
  variant?: 'ink' | 'mandarina' | 'blanco'
  /** Tamaño en pixels (el SVG escala) */
  size?: number | string
  /** Texto alternativo */
  alt?: string
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'ink',
  size: 40,
  alt: 'Forseti',
})

const src = computed(() => {
  switch (props.variant) {
    case 'mandarina': return '/logos/forseti-logo-mandarina.svg'
    case 'blanco':    return '/logos/forseti-logo-blanco.svg'
    case 'ink':
    default:          return '/logos/forseti-logo-ink.svg'
  }
})

const sizeValue = computed(() => typeof props.size === 'number' ? `${props.size}px` : props.size)
</script>

<template>
  <img
    :src="src"
    :alt="alt"
    :width="sizeValue"
    :height="sizeValue"
    class="forseti-logo"
    loading="eager"
    decoding="async"
  />
</template>

<style scoped>
.forseti-logo {
  display: inline-block;
  vertical-align: middle;
}
</style>
