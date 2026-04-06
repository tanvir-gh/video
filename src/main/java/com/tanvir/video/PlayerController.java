package com.tanvir.video;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PlayerController {

    @GetMapping("/")
    public String player() {
        return "player";
    }
}
