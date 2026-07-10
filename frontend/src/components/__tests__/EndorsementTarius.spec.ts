import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EndorsementTarius from '../EndorsementTarius.vue'

describe('EndorsementTarius', () => {
  it('renderiza el texto "un producto de TARIUS"', () => {
    const wrapper = mount(EndorsementTarius)
    expect(wrapper.text()).toContain('un producto de')
    expect(wrapper.text()).toContain('TARIUS')
  })

  it('tiene la clase endorsement-tarius', () => {
    const wrapper = mount(EndorsementTarius)
    expect(wrapper.classes()).toContain('endorsement-tarius')
  })
})
